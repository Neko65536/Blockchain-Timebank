package org.example.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Date;
import java.util.Map;

public class JwtUtils {

    private static volatile byte[] signKey;
    private static Long expire = 43200000L;

    public static void configureSecret(String secret) {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET必须配置为至少32字节的随机密钥");
        }
        signKey = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] signingKey() {
        if (signKey == null) throw new IllegalStateException("JWT密钥尚未配置");
        return signKey;
    }

    /**
     * 生成JWT令牌
     * @param claims JWT第二部分负载 payload 中存储的内容
     * @return
     */
    public static String generateJwt(Map<String, Object> claims){
        String jwt = Jwts.builder()
                .addClaims(claims)
                .signWith(SignatureAlgorithm.HS256, signingKey())
                .setExpiration(new Date(System.currentTimeMillis() + expire))
                .compact();
        return jwt;
    }

    /**
     * 解析JWT令牌
     * @param jwt JWT令牌
     * @return JWT第二部分负载 payload 中存储的内容
     */
    public static Claims parseJWT(String jwt){
        Claims claims = Jwts.parser()
                .setSigningKey(signingKey())
                .parseClaimsJws(jwt)
                .getBody();
        return claims;
    }
}
