package org.example.timecoinweb.service;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 协议版本留痕：版本号 + 时间戳 + 来源哈希，幂等且不可改；与注册同事务。 */
class AgreementServiceTests {
    JdbcTemplate jdbc; AgreementService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:agree" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        service = new AgreementService(jdbc);
    }

    @Test void currentVersionIsSeededAndQueryable() {
        Map<String, Object> current = service.current();
        assertEquals("v1", current.get("version"));
        assertNotNull(current.get("contentHash"));
        assertTrue(current.get("effectiveAt") != null);
    }

    @Test void acceptanceIsRecordedWithHashAndIdempotent() {
        service.recordAcceptance(1, "v1", true, "register");
        assertTrue(service.hasAccepted(1, "v1"));
        var rows = jdbc.queryForList("select * from user_agreement_accept where user_id=1");
        assertEquals(1, rows.size());
        assertEquals("v1", rows.get(0).get("version"));
        assertNotNull(rows.get(0).get("accepted_at"));
        assertNotNull(rows.get(0).get("source_hash"));
        // 幂等：同版本重复同意不产生第二条
        service.recordAcceptance(1, "v1", true, "register");
        assertEquals(1, jdbc.queryForObject("select count(*) from user_agreement_accept where user_id=1", Integer.class));
        // 不可把同意改为拒绝
        assertThrows(Exception.class, () -> service.recordAcceptance(1, "v1", false, "register"));
        assertEquals(1, service.history(1).size());
    }

    @Test void unknownOrInactiveVersionIsRejectedAndBlankIsSkipped() {
        assertThrows(Exception.class, () -> service.recordAcceptance(2, "v9", true, "register"));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_agreement_accept", Integer.class));
        service.recordAcceptance(2, null, true, "register");   // 兼容旧注册调用
        assertEquals(0, jdbc.queryForObject("select count(*) from user_agreement_accept", Integer.class));
    }

    @Test void sourceHashIsStableForSameVersionAndSource() {
        service.recordAcceptance(3, "v1", true, "register");
        String h1 = jdbc.queryForObject("select source_hash from user_agreement_accept where user_id=3", String.class);
        service.recordAcceptance(4, "v1", true, "register");
        String h2 = jdbc.queryForObject("select source_hash from user_agreement_accept where user_id=4", String.class);
        assertEquals(h1, h2);
        assertTrue(service.hasAccepted(4, "v1"));
    }
}
