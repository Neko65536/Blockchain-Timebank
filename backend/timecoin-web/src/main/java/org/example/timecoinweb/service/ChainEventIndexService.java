package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.timecoinweb.config.BlockchainProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * N5：链上事件离线索引。
 * - sync() 由定时任务在后台调用 listMintTransferEvents 拉取并按 (txHash,logIndex) 幂等入库；
 *   创世扫描 dari 用户请求路径移到后台，前端查询只读索引表。
 * - 事件解析逻辑完全复用 TimeCoinChainService，不重复实现 ABI 解码。
 * - 游标表记录上次成功同步高度（当前实现为"最近 limit 条"游标：以最新事件 txHash 作为水位标识），
 *   FISCO 的 1 万区块回扫上限仍在底层，但不再触发用户请求超时。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainEventIndexService {
    private final JdbcTemplate jdbc;
    private final TimeCoinChainService chain;
    private final BlockchainProperties props;

    @Value("${blockchain.event-index-limit:500}")
    private int indexLimit;

    /** 后台增量同步：拉最近事件幂等入库；返回本次新增条数。链不可用时跳过。 */
    public int sync() {
        if (!props.isEnabled() || !chain.isChainReady()) return 0;
        List<Map<String, Object>> events;
        try {
            events = chain.listMintTransferEvents(indexLimit);
        } catch (Exception e) {
            log.warn("事件索引同步失败，保留旧索引: {}", e.getMessage());
            return 0;
        }
        int added = 0;
        for (Map<String, Object> e : events) {
            String txHash = str(e.get("txHash"));
            String logIndex = str(e.getOrDefault("logIndex", "0"));
            Integer n = jdbc.update("insert ignore into chain_event_index"
                            + "(tx_hash,log_index,event_type,block_number,event_time,amount,parties) values(?,?,?,?,?,?,?)",
                    txHash, logIndex, str(e.get("type")), str(e.get("blockNumber")),
                    str(e.get("time")), str(e.get("amount")), str(e.get("parties")));
            if (n != null && n > 0) added++;
        }
        jdbc.update("update chain_event_cursor set last_block=?,updated_at=CURRENT_TIMESTAMP where backend=?",
                str(events.isEmpty() ? "0" : events.get(0).get("blockNumber")),
                props.isFisco() ? "fisco" : "ethereum");
        return added;
    }

    /** 查询索引（最新在前）；limit 上限 200，避免一次拉太多。 */
    public List<Map<String, Object>> query(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.queryForList("select tx_hash as txHash,event_type as eventType,block_number as blockNumber,"
                + "event_time as eventTime,amount,parties from chain_event_index order by created_at desc limit " + safe);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
