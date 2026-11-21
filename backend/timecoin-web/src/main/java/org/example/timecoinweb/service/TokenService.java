package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import org.example.pojo.User;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * N1 会话与令牌治理。
 * - access token 带 tv（令牌版本）；登出/改密即递增 user.token_version，旧 token 全部失效。
 * - refresh token 只存 SHA-256 哈希到 refresh_token 表；轮换后旧 token 标记 used，重放即视为盗用，撤销整个 family。
 * 不引入 Redis 依赖（dev 无 Redis），刷新令牌状态落库，与业务库同事务边界简单。
 */
@Service
@RequiredArgsConstructor
public class TokenService {
    private final JdbcTemplate jdbc;
    private final UserMapper userMapper;

    /** N1：refresh token TTL，默认 7 天；生产可缩短。 */
    @Value("${app.auth.refresh-ttl-ms:604800000}")
    private long refreshTtlMs;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成 access token（带当前令牌版本）。 */
    public String accessToken(User user) {
        return JwtUtils.generateJwt(Map.of(
                "id", user.getId(),
                "role", user.getRole(),
                "username", user.getUsername() == null ? "" : user.getUsername()),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion());
    }

    /** 签发一对令牌；refresh 明文只返回一次，库内只留哈希。 */
    public String issueRefreshToken(Integer userId) {
        String raw = newToken();
        String family = UUID.randomUUID().toString();
        jdbc.update("insert into refresh_token(id,user_id,token_hash,family_id,used,revoked,expires_at) "
                        + "values(?,?,?,?,0,0,?)",
                UUID.randomUUID().toString(), userId, sha256(raw), family,
                new java.sql.Timestamp(System.currentTimeMillis() + refreshTtlMs));
        return raw;
    }

    /**
     * 轮换 refresh token：成功返回新明文；旧 token 标记 used。
     * 若检测到已使用的 token 再次提交（重放/盗用），撤销该 family 并递增令牌版本，拒绝。
     */
    public String rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw unauthorized();
        String hash = sha256(rawToken);
        var rows = jdbc.query("select id,user_id,family_id,used,revoked,expires_at from refresh_token where token_hash=?",
                (rs, n) -> new Object[]{rs.getString("id"), rs.getInt("user_id"), rs.getString("family_id"),
                        rs.getInt("used"), rs.getInt("revoked"), rs.getTimestamp("expires_at").toLocalDateTime()}, hash);
        if (rows.size() != 1) throw unauthorized();
        Object[] row = rows.get(0);
        if (((Number) row[3]).intValue() != 0 || ((Number) row[4]).intValue() != 0
                || ((java.time.LocalDateTime) row[5]).isBefore(java.time.LocalDateTime.now())) {
            // 重放：撤销整个 family + 递增令牌版本，使该用户所有 token 立即失效
            jdbc.update("update refresh_token set revoked=1 where family_id=?", row[2]);
            userMapper.bumpTokenVersion((Integer) row[1]);
            throw unauthorized();
        }
        jdbc.update("update refresh_token set used=1 where id=?", row[0]);
        return issueRefreshToken((Integer) row[1]);
    }

    /** 轮换前按明文哈希查 userId；不存在/已用/已撤销/过期返回 null。 */
    public Integer findUserIdByRefresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        Integer id = jdbc.query("select user_id from refresh_token where token_hash=? and used=0 and revoked=0 and expires_at>?",
                rs -> rs.next() ? rs.getInt("user_id") : null, sha256(rawToken),
                new java.sql.Timestamp(System.currentTimeMillis()));
        return id;
    }

    /** 登出：撤销该用户全部 refresh token + 递增令牌版本，所有旧 token 立即失效。 */
    public void logout(Integer userId) {
        jdbc.update("update refresh_token set revoked=1 where user_id=?", userId);
        userMapper.bumpTokenVersion(userId);
    }

    /** 改密/冻结联动：仅递增令牌版本。 */
    public void revokeAll(Integer userId) {
        jdbc.update("update refresh_token set revoked=1 where user_id=?", userId);
        userMapper.bumpTokenVersion(userId);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
