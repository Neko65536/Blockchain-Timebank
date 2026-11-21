package org.example.timecoinweb.service;

import org.example.timecoinweb.config.BlockchainProperties;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P1 专项：N6 运维告警幂等/恢复、N5 事件索引幂等、N7 默认后端为 fisco。 */
class ReliabilityTests {
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:rel" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("schema-h2.sql"),
                new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void n7DefaultBackendIsFisco() {
        assertEquals("fisco", new BlockchainProperties().getBackend());
    }

    @Test
    void n6AlertIsIdempotentAndCanRecover() {
        OpsAlertService alerts = new OpsAlertService(jdbc);
        alerts.raise("STUCK_SUBMISSION", "0xabc", "WARN", "card");
        alerts.raise("STUCK_SUBMISSION", "0xabc", "WARN", "card again");
        assertEquals(1, jdbc.queryForObject("select count(*) from ops_alert where kind='STUCK_SUBMISSION' and resolved=0", Integer.class));
        assertEquals(1, alerts.openAlerts(10).size());
        alerts.resolve("STUCK_SUBMISSION", "0xabc");
        assertEquals(0, alerts.openAlerts(10).size());
        // 恢复后可重新告警
        alerts.raise("STUCK_SUBMISSION", "0xabc", "WARN", "new");
        assertEquals(1, alerts.openAlerts(10).size());
    }

    @Test
    void n5EventIndexUpsertIsIdempotent() {
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert ignore into chain_event_index(tx_hash,log_index,event_type,block_number,amount) "
                    + "values(?,?,?,?,?)", "0xtx1", "0", "Mint", "5", "10");
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from chain_event_index where tx_hash='0xtx1'", Integer.class));
        // 不同 logIndex 视为不同事件
        jdbc.update("insert ignore into chain_event_index(tx_hash,log_index,event_type,block_number,amount) "
                + "values(?,?,?,?,?)", "0xtx1", "1", "Transfer", "5", "10");
        assertEquals(2, jdbc.queryForObject("select count(*) from chain_event_index where tx_hash='0xtx1'", Integer.class));
    }

    @Test
    void n4JobProgressTableRoundTrip() {
        jdbc.update("insert into batch_reward_job(job_id,owner_id,activity_id,state,total) values(?,?,?,?,?)",
                "job-1", 1, 10, "QUEUED", 3);
        jdbc.update("update batch_reward_job set state='DONE',done=3,failed=0,result_json='{}' where job_id='job-1'");
        var row = jdbc.queryForMap("select state,done,failed from batch_reward_job where job_id='job-1'");
        assertEquals("DONE", row.get("STATE") == null ? row.get("state") : row.get("STATE"));
        assertEquals(3, ((Number) (row.get("DONE") == null ? row.get("done") : row.get("DONE"))).intValue());
    }
}
