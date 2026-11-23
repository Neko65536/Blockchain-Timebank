package org.example.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具（jjwt 0.12.x API）。
 * 密钥通过 {@link #configureSecret(String)} 转换为 {@link SecretKey}，签名固定 HS256。
 */
public class JwtUtils {

    private static volatile SecretKey signKey;
    private static volatile long expire = 43200000L;

    /** N1：JWT 有效期可配置（毫秒），供生产缩短 access token 寿命。 */
    public static void configureExpire(long expireMs) {
        if (expireMs <= 0) throw new IllegalArgumentException("JWT 有效期必须为正数");
        expire = expireMs;
    }

    public static void configureSecret(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET必须配置为至少32字节的随机密钥");
        }
        signKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKey signingKey() {
        if (signKey == null) throw new IllegalStateException("JWT密钥尚未配置");
        return signKey;
    }

    /**
     * 生成 JWT 令牌。N1：自动注入 jti（令牌唯一标识）与 tv（令牌版本），
     * 供拦截器校验令牌是否已被登出/改密/冻结撤销。
     *
     * @param claims JWT 负载中存储的内容
     * @return JWT 字符串
     */
    public static String generateJwt(Map<String, Object> claims){
        Map<String, Object> full = new HashMap<>(claims);
        if (!full.containsKey("jti")) full.put("jti", UUID.randomUUID().toString());
        // tv 由调用方显式传入；未传时默认 0（兼容历史调用与测试 token）
        full.putIfAbsent("tv", 0);
        return Jwts.builder()
                .claims(full)
                .signWith(signingKey(), Jwts.SIG.HS256)
                .expiration(new Date(System.currentTimeMillis() + expire))
                .compact();
    }

    /** N1：签发带指定令牌版本的 access token。 */
    public static String generateJwt(Map<String, Object> claims, int tokenVersion){
        Map<String, Object> full = new HashMap<>(claims);
        full.put("tv", tokenVersion);
        return generateJwt(full);
    }

    /**
     * 解析 JWT 令牌
     * @param jwt JWT令牌
     * @return JWT 第二部分负载 payload 中存储的内容
     */
    public static Claims parseJWT(String jwt){
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }
}
