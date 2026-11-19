package org.example.timecoinweb.controller;
import lombok.*;
import org.example.pojo.Result;
import org.example.timecoinweb.agent.ReviewAgent;
import org.example.timecoinweb.rag.AiTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.util.List;
@RestController
@RequestMapping("/administrator/agent/reviews")
@RequiredArgsConstructor
public class ReviewAgentController {
    private final ReviewAgent agent;private final AiTaskExecutor executor;
    @GetMapping("/candidates") public Result candidates(@RequestAttribute("authenticatedUserId") int actor){return Result.success(agent.candidates(actor));}
    @GetMapping public Result list(@RequestAttribute("authenticatedUserId") int actor){return Result.success(agent.list(actor));}
    @PostMapping public DeferredResult<Result> screen(@RequestAttribute("authenticatedUserId") int actor,@RequestHeader("Idempotency-Key") String key,@RequestBody Input body){return executor.submit(()->{try{return agent.screen(actor,key,body.ids);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("预筛失败",e);}});}
    @PostMapping("/{id}/decision") public Result decide(@RequestAttribute("authenticatedUserId") int actor,@PathVariable String id,@RequestBody Input body){return Result.success(agent.decide(actor,id,body.decision,body.note,body.confirmed));}
    @Data public static class Input {private List<Integer> ids;private String decision;private String note;private boolean confirmed;}
}
