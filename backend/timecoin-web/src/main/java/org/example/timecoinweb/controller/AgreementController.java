package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.service.AgreementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 协议版本查询：current 公开（注册前读取），mine 需登录（/info 前缀）。 */
@RestController
@RequiredArgsConstructor
public class AgreementController {
    private final AgreementService agreements;

    @GetMapping("/agreement/current")
    public Result current() { return Result.success(agreements.current()); }

    @GetMapping("/info/agreement/mine")
    public Result mine(@org.springframework.web.bind.annotation.RequestAttribute("authenticatedUserId") int actor) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("acceptances", agreements.history(actor));
        data.put("currentVersion", agreements.current().get("version"));
        return Result.success(data);
    }
}
