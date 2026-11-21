package org.example.timecoinweb.controller;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.agent.SettlementAgent;
import org.example.timecoinweb.agent.SettlementConfirmation;
import org.example.timecoinweb.rag.AiTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

/** 结算答谢Agent：只读预检 + 人工确认发放；路径归老人端，拦截器按角色放行。 */
@RestController
@RequestMapping("/users/old/agent/settlement")
@RequiredArgsConstructor
public class SettlementAgentController {
    private final SettlementAgent agent;
    private final SettlementConfirmation confirmation;
    private final AiTaskExecutor executor;

    @PostMapping("/prepare")
    public DeferredResult<Result> prepare(@RequestAttribute("authenticatedUserId") int actor,
                                          @RequestHeader("Idempotency-Key") String key, @RequestBody Input input) {
        return executor.submit(() -> {
            try { return Result.success(agent.prepare(actor, key, input.activityId)); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("预检生成失败", e); }
        });
    }
    @GetMapping("/{id}") public Result get(@RequestAttribute("authenticatedUserId") int actor, @PathVariable String id) {
        return Result.success(agent.get(actor, id));
    }
    @PostMapping("/{id}/confirm") public Result confirm(@RequestAttribute("authenticatedUserId") int actor,
                                                        @PathVariable String id, @RequestBody Input input) {
        return Result.success(confirmation.confirm(actor, id, input.revision, input.confirmed));
    }
    @Data public static class Input { private int activityId; private int revision; private boolean confirmed; }
}
