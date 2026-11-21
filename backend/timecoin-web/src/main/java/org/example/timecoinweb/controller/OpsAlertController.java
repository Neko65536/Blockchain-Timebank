package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.service.OpsAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** N6：管理员查看未恢复的运维告警（卡单/对账不一致）。 */
@RestController
@RequiredArgsConstructor
public class OpsAlertController {
    private final OpsAlertService alerts;

    @GetMapping("/administrator/ops/alerts")
    public Result list(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(alerts.openAlerts(Math.max(1, Math.min(limit, 500))));
    }
}
