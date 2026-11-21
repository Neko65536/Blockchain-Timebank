package org.example.timecoinweb.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** J9：密码最小长度，默认 8；<=0 关闭复杂度校验（兼容旧环境） */
    @org.springframework.beans.factory.annotation.Value("${app.password.min-length:8}")
    private int minLength;

    /** J9：是否要求同时包含字母与数字，默认开启 */
    @org.springframework.beans.factory.annotation.Value("${app.password.require-alnum:true}")
    private boolean requireAlnum;

    public String encode(String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        validateStrength(rawPassword);
        return encoder.encode(rawPassword);
    }

    /** J9：新密码强度校验：长度 + 字母数字组合 + 弱口令黑名单。 */
    private void validateStrength(String password) {
        if (minLength > 0 && password.length() < minLength) {
            throw new IllegalArgumentException("密码长度不能少于 " + minLength + " 位");
        }
        if (requireAlnum && !(password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit))) {
            throw new IllegalArgumentException("密码必须同时包含字母和数字");
        }
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("12345678", "password", "123456789", "qwerty123", "admin123", "11111111").contains(lower)) {
            throw new IllegalArgumentException("密码过于简单，请更换");
        }
    }

    public String encodeIfNecessary(String password) {
        if (!StringUtils.hasText(password)) {
            return password;
        }
        // 历史明文密码升级不套用新强度策略，避免旧账号无法登录；强度只约束新设置/注册密码。
        return isEncoded(password) ? password : encoder.encode(password);
    }

    public boolean matches(String rawPassword, String storedPassword) {
        if (!StringUtils.hasText(rawPassword) || !StringUtils.hasText(storedPassword)) {
            return false;
        }
        if (isEncoded(storedPassword)) {
            return encoder.matches(rawPassword, storedPassword);
        }
        return rawPassword.equals(storedPassword);
    }

    public boolean needsUpgrade(String storedPassword) {
        return StringUtils.hasText(storedPassword) && !isEncoded(storedPassword);
    }

    private boolean isEncoded(String password) {
        return password.startsWith("$2a$")
                || password.startsWith("$2b$")
                || password.startsWith("$2y$");
    }
}
