package org.example.timecoinweb.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.config.ShardRouting;
import org.example.timecoinweb.service.OpsAlertService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * N6：卡单巡检。
 * - chain_submission 处于 PREPARED/PROCESSING 且超过阈值未推进 → 落运维告警，等人工 resume，不自动改链上状态。
 * - 对账不一致由 CoinBalanceLedgerService 已写 chain_reconcile_log，本任务额外按最新一次不一致落 ops_alert。
 * 默认关闭（需 blockchain.enabled=true）；阈值可配，避免开发期误报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blockchain.enabled", havingValue = "true")
public class StuckSubmissionTask {
    private final JdbcTemplate jdbc;
    private final OpsAlertService alerts;
    private final BlockchainProperties props;
    private final ShardRouting routing;

    /** 卡单阈值（分钟）：超过该时长仍处于 PREPARED/PROCESSING 即告警。 */
    @org.springframework.beans.factory.annotation.Value("${blockchain.stuck-alert-minutes:30}")
    private long stuckMinutes;

    @Scheduled(fixedDelayString = "${blockchain.stuck-scan-ms:600000}", initialDelayString = "${blockchain.stuck-scan-ms:600000}")
    public void scan() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(stuckMinutes * 60));
        String range = routing.all("created_at");
        var rows = jdbc.queryForList(
                "select tx_hash,state,created_at from chain_submission where " + range
                        + " and state in ('PREPARED','PROCESSING') and created_at < ? limit 100", cutoff);
        for (var row : rows) {
            String hash = String.valueOf(row.get("tx_hash"));
            String detail = "state=" + row.get("state") + ",createdAt=" + row.get("created_at");
            alerts.raise("STUCK_SUBMISSION", hash, "WARN", detail);
            log.warn("发现卡住的链上凭据: {} {}", hash, detail);
        }
        // 已确认/失败的凭据若曾告警则自动恢复
        var resolved = jdbc.queryForList(
                "select tx_hash from chain_submission where " + range
                        + " and state in ('CONFIRMED','FAILED') and created_at < ? limit 100", cutoff);
        for (var row : resolved) {
            alerts.resolve("STUCK_SUBMISSION", String.valueOf(row.get("tx_hash")));
        }
    }
}
