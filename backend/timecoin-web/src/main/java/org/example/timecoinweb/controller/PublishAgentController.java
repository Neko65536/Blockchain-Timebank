package org.example.timecoinweb.controller;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.example.timecoinweb.agent.*;
import org.example.timecoinweb.rag.AiTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/users/old/agent/publish")
@RequiredArgsConstructor
public class PublishAgentController {
    private final PublishAgent agent;
    private final PublishConfirmation confirmation;
    private final AiTaskExecutor executor;
    @PostMapping public DeferredResult<Result> create(@RequestAttribute("authenticatedUserId") int actor,@RequestHeader("Idempotency-Key") String key,@RequestBody Input input){
        return executor.submit(()->{try{return agent.create(actor,key,input.text);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("模型生成失败",e);}});
    }
    @GetMapping("/{id}") public Result get(@RequestAttribute("authenticatedUserId") int actor,@PathVariable String id){return Result.success(agent.get(actor,id));}
    @PutMapping("/{id}") public Result revise(@RequestAttribute("authenticatedUserId") int actor,@PathVariable String id,@RequestBody Input input){return Result.success(agent.revise(actor,id,input.revision,input.draft));}
    @PostMapping("/{id}/confirm") public Result confirm(@RequestAttribute("authenticatedUserId") int actor,@PathVariable String id,@RequestBody Input input){return Result.success(confirmation.confirm(actor,id,input.revision,input.acceptedFee,input.confirmed));}
    @Data public static class Input {private String text;private int revision;private JsonNode draft;private String acceptedFee;private boolean confirmed;}
}
