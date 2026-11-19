package org.example.timecoinweb.controller;
import lombok.*;
import org.example.pojo.Result;
import org.example.timecoinweb.agent.AnalyticsAgent;
import org.example.timecoinweb.rag.AiTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
@RestController
@RequestMapping("/administrator/agent/analytics")
@RequiredArgsConstructor
public class AnalyticsAgentController {
    private final AnalyticsAgent agent;private final AiTaskExecutor executor;
    @PostMapping public DeferredResult<Result> answer(@RequestAttribute("authenticatedUserId") int actor,@RequestBody Input body){return executor.submit(()->{try{return agent.answer(actor,body.question);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("运营查询失败",e);}});}
    @Data public static class Input {private String question;}
}
