package org.example.timecoinweb.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 现场签到凭证：由服务端签发、绑定活动与志愿者的一次性短时效口令。
 *
 * 设计要点（J2）：浏览器 GPS 可被模拟器伪造，纯距离比较无法证明到场。
 * 因此改由「在场的老人/管理员」在活动现场签发口令，志愿者现场提交；
 * 口令 HMAC 签名且短时效，志愿者无法自行伪造，也难以转发复用。
 * 残余风险：老人与志愿者线下串通，需靠抽查/多签复核兜底，不在本服务范围内。
 */
@Service
public class SignInCredentialService {

    /** 口令有效期，默认 5 分钟；现场签发后应立即使用 */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final byte[] secret;

    public SignInCredentialService(@Value("${security.jwt-secret:}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("签到凭证需要 JWT_SECRET（至少 32 字节）派生密钥");
        }
        // 与 JWT 密钥做域分离，避免凭证签名与登录 token 互相影响
        this.secret = hmacSha256(jwtSecret.getBytes(StandardCharsets.UTF_8),
                "timebank-signin-credential".getBytes(StandardCharsets.UTF_8));
    }

    /** 老人/管理员为指定活动的指定志愿者签发一次性签到口令。 */
    public String issue(int activityId, int volunteerId) {
        long expiresAt = Instant.now().plus(TTL).getEpochSecond();
        String payload = activityId + ":" + volunteerId + ":" + expiresAt + ":" + java.util.UUID.randomUUID();
        String mac = HexFormat.of().formatHex(hmacSha256(secret, payload.getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + ":" + mac).getBytes(StandardCharsets.UTF_8));
    }

    /** 校验口令：签名、活动、志愿者、有效期。失败抛异常，由调用方转成业务错误。 */
    public void verify(String credential, int activityId, int volunteerId) {
        if (credential == null || credential.isBlank()) throw new IllegalArgumentException("签到凭证不能为空");
        final String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(credential), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("签到凭证格式错误");
        }
        int idx = decoded.lastIndexOf(':');
        if (idx <= 0) throw new IllegalArgumentException("签到凭证格式错误");
        String payload = decoded.substring(0, idx);
        String mac = decoded.substring(idx + 1);
        String expected = HexFormat.of().formatHex(hmacSha256(secret, payload.getBytes(StandardCharsets.UTF_8)));
        if (!constantTimeEquals(expected, mac)) throw new IllegalArgumentException("签到凭证签名无效");

        String[] parts = payload.split(":", 4);
        if (parts.length != 4) throw new IllegalArgumentException("签到凭证格式错误");
        try {
            int a = Integer.parseInt(parts[0]);
            int v = Integer.parseInt(parts[1]);
            long exp = Long.parseLong(parts[2]);
            if (a != activityId || v != volunteerId) throw new IllegalArgumentException("签到凭证与当前活动/志愿者不匹配");
            if (Instant.now().getEpochSecond() > exp) throw new IllegalArgumentException("签到凭证已过期，请重新获取");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("签到凭证格式错误");
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
