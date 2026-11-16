package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.service.SettlementService;
import org.example.utils.JwtUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/info/settlements")
@RequiredArgsConstructor
public class SettlementController {
    private final SettlementService settlements;

    @GetMapping
    public Result list(@RequestHeader("token") String token) {
        return Result.success(settlements.list((Integer) JwtUtils.parseJWT(token).get("id"), false));
    }
    @GetMapping("/{id}")
    public Result get(@PathVariable String id, @RequestHeader("token") String token) {
        return Result.success(settlements.get(id, (Integer) JwtUtils.parseJWT(token).get("id"), false));
    }
}
