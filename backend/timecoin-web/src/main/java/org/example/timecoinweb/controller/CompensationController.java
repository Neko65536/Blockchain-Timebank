package org.example.timecoinweb.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.CompensationRecord;
import org.example.pojo.Result;
import org.example.timecoinweb.mapper.CompensationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/administrator/compensation")
public class CompensationController {

    @Autowired
    private CompensationMapper compensationMapper;
    @Autowired
    private org.example.timecoinweb.service.CompensationService compensationService;

    @GetMapping("/list")
    public Result getList(@RequestParam(required = false) Short status) {
        try {
            List<CompensationRecord> list;
            if (status != null) {
                if (status == 0) {
                    list = compensationMapper.selectPending();
                } else {
                    list = compensationMapper.selectAll().stream()
                            .filter(r -> r.getStatus() != null && r.getStatus().equals(status))
                            .collect(Collectors.toList());
                }
            } else {
                list = compensationMapper.selectAll();
            }
            Map<String, Object> data = new HashMap<>();
            data.put("list", list);
            return Result.success(data);
        } catch (Exception e) {
            log.warn("查询追讨记录列表失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * J5：创建违约追讨记录（答谢后服务未履约时由管理员发起）。
     */
    @PostMapping
    public Result create(@RequestBody Map<String, Object> request) {
        try {
            int activityId = ((Number) request.get("activityId")).intValue();
            int volunteerTableId = ((Number) request.get("volunteerTableId")).intValue();
            Integer volunteerUserId = request.get("volunteerUserId") == null ? null
                    : ((Number) request.get("volunteerUserId")).intValue();
            int elderUserId = ((Number) request.get("elderUserId")).intValue();
            int amount = ((Number) request.get("amount")).intValue();
            var record = compensationService.create(activityId, volunteerTableId, volunteerUserId, elderUserId, amount);
            return Result.success(record);
        } catch (Exception e) {
            log.warn("创建追讨记录失败", e);
            return Result.error("创建失败: " + e.getMessage());
        }
    }

    /**
     * J5：处理追讨，action=1 执行链上扣回并置「已追讨」，action=2 平台核销。
     * 终态重复处理幂等，不重复扣回。
     */
    @PostMapping("/process/{id}")
    public Result processRecord(@PathVariable Integer id, @RequestBody Map<String, Object> request) {
        try {
            short action = Short.parseShort(request.get("action").toString());
            short status = compensationService.process(id, action);
            return Result.success(Map.of("status", status));
        } catch (Exception e) {
            log.warn("处理追讨记录失败 id={}", id, e);
            return Result.error("处理失败: " + e.getMessage());
        }
    }
}
