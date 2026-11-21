package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.service.PasswordService;
import org.example.timecoinweb.service.RegisterService;
import org.example.timecoinweb.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** N1/N2 会话端点：刷新令牌、登出、修改密码。均在 /info 或公开白名单下。 */
@RestController
@RequiredArgsConstructor
public class AuthController {
    private final TokenService tokenService;
    private final RegisterService registerService;
    private final UserMapper userMapper;
    private final PasswordService passwordService;

    /** 公开：用 refresh token 换取新的 access + refresh（轮换，重放检测）。 */
    @PostMapping("/auth/refresh")
    public Result refresh(@RequestBody Map<String, String> body) {
        String raw = body.get("refreshToken");
        Integer uid = tokenService.findUserIdByRefresh(raw);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        User u = userMapper.selectById(uid);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        String rotated = tokenService.rotate(raw);
        return Result.success(new org.example.pojo.LoginTokens(
                tokenService.accessToken(u), rotated, u.getRole(),
                u.getMustChangePassword() == null ? 0 : u.getMustChangePassword().intValue(), 0));
    }

    /** 登出：撤销该用户所有 refresh token + 递增令牌版本，旧 token 立即失效。 */
    @PostMapping("/info/logout")
    public Result logout(@RequestAttribute("authenticatedUserId") int actor) {
        tokenService.logout(actor);
        return Result.success();
    }

    /** 修改密码：校验旧密码 → 强度校验 → 更新 → 递增令牌版本使旧 token 失效 → 清除首登改密标志。 */
    @PostMapping("/info/password")
    public Result changePassword(@RequestAttribute("authenticatedUserId") int actor,
                                 @RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        User current = userMapper.selectById(actor);
        if (current == null || !passwordService.matches(oldPassword, current.getPassword())) {
            return Result.error("原密码错误");
        }
        // encode 内含 J9 强度校验；弱口令/过短会抛 IllegalArgumentException
        String encoded;
        try {
            encoded = passwordService.encode(newPassword);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        registerService.updatePassword(actor, encoded);
        tokenService.revokeAll(actor);
        return Result.success();
    }
}
