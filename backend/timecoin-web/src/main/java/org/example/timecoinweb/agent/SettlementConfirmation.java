package org.example.timecoinweb.agent;

import lombok.RequiredArgsConstructor;
import org.example.timecoinweb.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** 结算答谢的真实发放只在显式人工确认后调用既有批量答谢链路；不是模型可调用的工具。 */
@Service
@RequiredArgsConstructor
public class SettlementConfirmation {
    private final SettlementAgent agent;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final PublishAgent audit;
    private final PlatformTransactionManager manager;

    public Object confirm(int actor, String id, int revision, boolean confirmed) {
        agent.requireElder(actor);
        if (!confirmed) throw new IllegalArgumentException("必须由老人明确确认答谢发放");
        return new TransactionTemplate(manager).execute(tx -> {
            var rows = jdbc.queryForList("select * from agent_settlement where id=? and owner_id=? for update", id, actor);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提案不存在");
            Map<String, Object> row = rows.get(0);
            if (((Number) row.get("revision")).intValue() != revision)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "提案已变化，请重新核对");
            if ("CONFIRMED".equals(row.get("state")))
                return agent.read((String) row.get("result_json"));
            if (!"PREPARED".equals(row.get("state")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "提案状态不允许发放");

            JsonNode proposal = agent.read((String) row.get("proposal"));
            // 发放前再次核对乐观锁下的服务端事实，防止预检后活动/人数变化。
            if (!proposal.path("eligible").asBoolean(false))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "预检未通过：活动未结束、无已签到志愿者或预算/余额不满足，不能发放");
            int activityId = proposal.path("activityId").intValue();
            Object result = users.batchCompleteVolunteers(actor, activityId);
            jdbc.update("update agent_settlement set state='CONFIRMED',result_json=? where id=?", agent.encode(result), id);
            audit.audit(id, actor, "HUMAN_CONFIRM_SETTLEMENT", "老人确认答谢；结果以逐笔结算状态为准");
            return result;
        });
    }
}
