package org.example.timecoinweb.service;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.CompensationRecord;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.CompensationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * J5：违约补偿闭环。
 * 答谢后服务未履约 → 管理员创建追讨记录 → 链上从志愿者账户扣回时间币至老人账户 → 状态 1；
 * 无法扣回（余额不足/链未就绪）时保持待追讨并记录原因；也可由平台核销置 2。
 * 状态机 0(待追讨) → 1(已追讨)/2(平台核销)，终态不可重复处理，保证幂等。
 */
@Service
@Slf4j
public class CompensationService {
    private final CompensationMapper mapper;
    private final TimeCoinChainService chain;
    private final BlockchainProperties properties;

    public CompensationService(CompensationMapper mapper, TimeCoinChainService chain, BlockchainProperties properties) {
        this.mapper = mapper;
        this.chain = chain;
        this.properties = properties;
    }

    /** 创建追讨记录（幂等由调用方的业务幂等键约束）。 */
    public CompensationRecord create(int activityId, int volunteerTableId, Integer volunteerUserId,
                                     int elderUserId, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("追讨金额必须为正");
        CompensationRecord record = new CompensationRecord();
        record.setActivityId(activityId);
        record.setVolunteerTableId(volunteerTableId);
        record.setVolunteerUserId(volunteerUserId);
        record.setElderUserId(elderUserId);
        record.setAmount(amount);
        record.setStatus((short) 0);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        mapper.insert(record);
        return record;
    }

    /**
     * 处理追讨：action=1 执行链上扣回（志愿者→老人），成功置 1；
     * action=2 平台核销置 2（不执行链上操作）。终态重复处理直接返回当前状态（幂等）。
     */
    @Transactional
    public short process(int id, short action) {
        CompensationRecord record = mapper.lockById(id);
        if (record == null) throw new IllegalStateException("追讨记录不存在");
        if (record.getStatus() != null && record.getStatus() != 0) {
            // 终态幂等：不重复扣回
            return record.getStatus();
        }
        if (action != 1 && action != 2) throw new IllegalArgumentException("action 必须为 1(追讨) 或 2(核销)");
        short next;
        if (action == 1) {
            if (!properties.isEnabled()) throw new IllegalStateException("区块链未启用，无法执行链上扣回");
            if (record.getVolunteerUserId() == null) throw new IllegalStateException("缺少志愿者账户，无法执行链上扣回");
            try {
                chain.transfer(String.valueOf(record.getVolunteerUserId()), String.valueOf(record.getElderUserId()),
                        BigInteger.valueOf(record.getAmount()), "COMPENSATION", "compensation:" + id);
            } catch (Exception e) {
                throw new IllegalStateException("链上扣回失败，记录保持待追讨：" + e.getMessage(), e);
            }
            next = 1;
        } else {
            // 平台核销：不执行链上操作，由平台承担损失
            next = 2;
        }
        mapper.updateStatus(id, next, LocalDateTime.now());
        return next;
    }
}
