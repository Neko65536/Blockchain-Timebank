package org.example.timecoinweb.controller;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.service.ChainEventIndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** N5：链上事件查询优先读离线索引，避免用户请求触发创世扫描。 */
@RestController
@RequiredArgsConstructor
public class ChainEventController {
    private final ChainEventIndexService index;

    @GetMapping("/administrator/chain/events")
    public Result events(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(index.query(limit));
    }
}
