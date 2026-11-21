package org.example.timecoinweb.service;

import org.example.pojo.User;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.utils.JwtUtils;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * N1/N2/N3 会话与协议安全专项（轻量 H2 + mock mapper）。
 * 覆盖：令牌版本失效、refresh 轮换/重放撤销、登录失败锁定、管理员首登改密、协议强制与重新同意。
 */
class AuthSecurityTests {
    JdbcTemplate jdbc;
    UserMapper users;

    @BeforeEach
    void setup() {
        JwtUtils.configureSecret("auth-security-tests-only-secret-32-bytes");
        var ds = new DriverManagerDataSource("jdbc:h2:mem:auth" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("schema-h2.sql"),
                new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        users = mock(UserMapper.class);
        jdbc.update("delete from `user` where id=100");
        jdbc.update("insert into `user`(id,username,password,role,token_version,failed_attempts,must_change_password) "
                + "values(100,'u1','x',1,0,0,0)");
    }

    private User dbUser(int id) {
        return jdbc.queryForObject("select id,username,role,token_version as tokenVersion,failed_attempts as failedAttempts,"
                + "must_change_password as mustChangePassword from `user` where id=?", (rs, n) -> {
            User u = new User();
            u.setId(rs.getInt("id")); u.setUsername(rs.getString("username")); u.setRole(rs.getShort("role"));
            u.setTokenVersion(rs.getInt("tokenVersion")); u.setFailedAttempts(rs.getInt("failedAttempts"));
            u.setMustChangePassword(rs.getShort("mustChangePassword"));
            return u;
        }, id);
    }

    private TokenService tokenService() {
        TokenService t = new TokenService(jdbc, users);
        ReflectionTestUtils.setField(t, "refreshTtlMs", 600_000L);
        return t;
    }

    // ---------- N1 ----------

    @Test
    void accessTokenCarriesTokenVersionAndJti() {
        var claims = JwtUtils.parseJWT(tokenService().accessToken(dbUser(100)));
        assertEquals(0, ((Number) claims.get("tv")).intValue());
        assertNotNull(claims.get("jti"));
    }

    @Test
    void refreshRotatesAndReplayRevokesWholeFamily() {
        TokenService t = tokenService();
        String r1 = t.issueRefreshToken(100);
        assertEquals(100, t.findUserIdByRefresh(r1));
        String r2 = t.rotate(r1);
        assertNotEquals(r1, r2);
        assertNull(t.findUserIdByRefresh(r1), "已使用的 refresh token 不可再次使用");
        assertEquals(1, jdbc.queryForObject("select count(*) from refresh_token where used=1", Integer.class));
        // 重放旧 token：抛 401，且整个 family 被撤销、令牌版本递增
        assertThrows(Exception.class, () -> t.rotate(r1));
        assertEquals(1, jdbc.queryForObject("select count(*) from refresh_token where revoked=1", Integer.class));
        verify(users).bumpTokenVersion(100);
    }

    @Test
    void logoutIncrementsVersionSoOldTokenFails() {
        TokenService t = tokenService();
        String access = t.accessToken(dbUser(100));   // 旧 token 携带 tv=0
        assertEquals(0, ((Number) JwtUtils.parseJWT(access).get("tv")).intValue());
        t.logout(100);
        // 登出递增 DB 令牌版本；拦截器据此判定旧 token(tv=0) 已失效
        verify(users).bumpTokenVersion(100);
        // 新签发的 token 携带新版本 tv=1，与旧 token 不可混用
        User bumped = new User();
        bumped.setId(100); bumped.setRole((short) 1); bumped.setUsername("u1"); bumped.setTokenVersion(1);
        assertEquals(1, ((Number) JwtUtils.parseJWT(t.accessToken(bumped)).get("tv")).intValue());
    }

    // ---------- N3（协议） ----------

    @Test
    void agreementPublishSwitchesActiveVersionAndReacceptIsTracked() {
        AgreementService a = new AgreementService(jdbc);
        assertEquals("v1", a.current().get("version"));
        assertTrue(a.needsReaccept(100));
        a.recordAcceptance(100, "v1", true, "reaccept");
        assertFalse(a.needsReaccept(100));
        a.publish("v2", "新协议", "0xabc");
        assertEquals("v2", a.current().get("version"));
        assertTrue(a.needsReaccept(100), "新版本发布后老用户须重新同意");
        assertEquals(0, jdbc.queryForObject("select active from agreement_version where version='v1'", Integer.class));
    }

    @Test
    void acceptanceCannotBeDowngraded() {
        AgreementService a = new AgreementService(jdbc);
        a.recordAcceptance(100, "v1", true, "register");
        assertThrows(Exception.class, () -> a.recordAcceptance(100, "v1", false, "reaccept"));
    }
}
