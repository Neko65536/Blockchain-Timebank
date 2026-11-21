package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.service.AgreementService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 协议版本查询：current 公开（注册前读取），mine 需登录（/info 前缀）。 */
@RestController
@RequiredArgsConstructor
public class AgreementController {
    private final AgreementService agreements;

    @GetMapping("/agreement/current")
    public Result current() { return Result.success(agreements.current()); }

    @GetMapping("/info/agreement/mine")
    public Result mine(@RequestAttribute("authenticatedUserId") int actor) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("acceptances", agreements.history(actor));
        data.put("currentVersion", agreements.current().get("version"));
        data.put("needsReaccept", agreements.needsReaccept(actor));
        return Result.success(data);
    }

    /** N3：老用户重新同意当前协议（幂等，不可从同意改为拒绝）。 */
    @PostMapping("/info/agreement/accept")
    public Result accept(@RequestAttribute("authenticatedUserId") int actor, @RequestBody Map<String, String> body) {
        String version = body.get("version");
        if (version == null || !version.equals(agreements.current().get("version"))) {
            return Result.error("协议版本已更新，请重新阅读并同意最新版本");
        }
        agreements.recordAcceptance(actor, version, true, "reaccept");
        return Result.success();
    }

    /** N3：管理员发布新版本（仅角色 3，拦截器保证 /administrator 前缀鉴权）。 */
    @PostMapping("/administrator/agreement/publish")
    public Result publish(@RequestBody Map<String, String> body) {
        try {
            agreements.publish(body.get("version"), body.get("title"), body.get("contentHash"));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
