package org.example.timecoinweb.agent;

import io.agentscope.core.hook.*;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.crypto.Hash;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Supplier;

/** 每次run独立；只记录计量和状态，不记录提示词、思维过程、工具原文或异常消息。 */
final class AgentTelemetry implements Hook {
    private static final Logger log=LoggerFactory.getLogger(AgentTelemetry.class);
    private final String traceId;
    private final int actor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Set<String> allowedTools;
    private final long started=System.nanoTime();
    private final Map<String,ToolSpan> pendingTools=new LinkedHashMap<>();
    private final Set<ModelSpan> pendingModels=new LinkedHashSet<>();
    private long inputTokens,outputTokens,modelDurationMs;
    private int modelCalls,usageReportedCalls,toolCalls,toolFailures;
    private boolean finished;
    private String promptHash,resultHash;

    AgentTelemetry(String traceId,int actor,JdbcTemplate jdbc,ObjectMapper json,List<AgentToolSpec> specs) {
        this.traceId=traceId;this.actor=actor;this.jdbc=jdbc;this.json=json;
        allowedTools=new HashSet<>();specs.forEach(s->allowedTools.add(s.name()));
    }

    synchronized void event(String event,Map<String,Object> values) {
        if("REACT_START".equals(event) && values.get("inputHash")!=null) promptHash=String.valueOf(values.get("inputHash"));
        Map<String,Object> detail=new LinkedHashMap<>(values);
        detail.put("traceId",traceId);
        String encoded=json.writeValueAsString(detail);
        org.slf4j.MDC.put("traceId",traceId);
        try {
            jdbc.update("insert into agent_audit(id,task_id,actor_id,tool,detail) values(?,?,?,?,?)",
                    UUID.randomUUID().toString(),traceId,actor,event,encoded);
            log.info("agent_event={} actorId={} detail={}",event,actor,encoded);
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    Flux<ChatResponse> observeModel(String model,Supplier<Flux<ChatResponse>> source) {
        return Flux.defer(()->{
            ModelSpan span;
            synchronized(this) {
                if(finished)return Flux.error(new IllegalStateException("Agent追踪已结束"));
                span=new ModelSpan(++modelCalls,model);pendingModels.add(span);
            }
            return Flux.defer(source)
                    // SDK返回的是本轮累计usage快照；不可对流中的每个快照相加。
                    .doOnNext(response->{synchronized(this){if(response.getUsage()!=null)span.usage=response.getUsage();}})
                    .doOnComplete(()->endModel(span,"SUCCESS"))
                    .doOnError(error->endModel(span,"FAILED"))
                    .doOnCancel(()->endModel(span,"CANCELLED"));
        });
    }

    private synchronized void endModel(ModelSpan span,String status) {
        if(!pendingModels.remove(span))return;
        long elapsed=elapsed(span.started);modelDurationMs+=elapsed;
        Map<String,Object> detail=new LinkedHashMap<>();
        detail.put("sequence",span.sequence);detail.put("model",span.model);
        detail.put("status",status);detail.put("durationMs",elapsed);
        detail.put("usageReported",span.usage!=null);
        if(span.usage!=null) {
            usageReportedCalls++;
            inputTokens+=span.usage.getInputTokens();outputTokens+=span.usage.getOutputTokens();
            detail.put("inputTokens",span.usage.getInputTokens());detail.put("outputTokens",span.usage.getOutputTokens());
            detail.put("totalTokens",(long)span.usage.getInputTokens()+span.usage.getOutputTokens());
        }
        event("MODEL_CALL",detail);
    }

    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.fromSupplier(()->{
            synchronized(this) {
                if(finished)return event;
                if(event instanceof PreActingEvent before) {
                    var use=before.getToolUse();
                    pendingTools.put(use.getId(),new ToolSpan(++toolCalls,use.getName(),System.nanoTime()));
                } else if(event instanceof PostActingEvent after) {
                    var result=after.getToolResult();
                    String status=result!=null && result.getState()==ToolResultState.SUCCESS?"SUCCESS"
                            :result!=null && result.getState()==ToolResultState.INTERRUPTED?"CANCELLED":"FAILED";
                    endTool(after.getToolUse().getId(),status);
                }
            }
            return event;
        });
    }

    private void endTool(String id,String status) {
        ToolSpan span=pendingTools.remove(id);if(span==null)return;
        if(!"SUCCESS".equals(status))toolFailures++;
        Map<String,Object> detail=new LinkedHashMap<>();
        detail.put("sequence",span.sequence);detail.put("tool",allowedTools.contains(span.name)?span.name:"UNREGISTERED");
        detail.put("toolCallIdHash",Hash.sha3String(String.valueOf(id)));
        detail.put("status",status);detail.put("durationMs",elapsed(span.started));
        event("TOOL_CALL",detail);
    }

    /** 由运行时在 finish 前设置终态结果哈希（不存结果原文）。 */
    synchronized void resultHash(String value){ this.resultHash=Hash.sha3String(value); }

    synchronized void finish(boolean success) {
        if(finished)return;
        finished=true;
        for(ModelSpan span:List.copyOf(pendingModels))endModel(span,"CANCELLED");
        for(String id:List.copyOf(pendingTools.keySet()))endTool(id,"CANCELLED");
        long totalDuration=elapsed(started);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("durationMs",totalDuration);summary.put("modelDurationMs",modelDurationMs);
        summary.put("modelCalls",modelCalls);summary.put("toolCalls",toolCalls);summary.put("toolFailures",toolFailures);
        summary.put("usageReportedCalls",usageReportedCalls);summary.put("usageMissingCalls",modelCalls-usageReportedCalls);
        summary.put("usageComplete",modelCalls>0 && usageReportedCalls==modelCalls);
        summary.put("inputTokens",inputTokens);summary.put("outputTokens",outputTokens);summary.put("totalTokens",inputTokens+outputTokens);
        event(success?"REACT_COMPLETE":"REACT_FAILED",summary);
        // 独立 trace 实体：与 agent_audit 细粒度 span 并存，一行汇总，便于按 traceId 关联查询。
        try {
            jdbc.update("insert into agent_trace(trace_id,actor_id,status,prompt_hash,result_hash,guardrail_result,model_calls,tool_calls,tool_failures,input_tokens,output_tokens,duration_ms) "
                            + "values(?,?,?,?,?,?,?,?,?,?,?,?)",
                    traceId,actor,success?"COMPLETE":"FAILED",promptHash,resultHash,"PASS",
                    modelCalls,toolCalls,toolFailures,inputTokens,outputTokens,totalDuration);
        } catch(Exception traceFailure) {
            // 可观测性写入失败不得改变业务结果。
            log.warn("agent_trace write failed traceId={}",traceId,traceFailure);
        }
    }

    private static long elapsed(long start){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    private static final class ModelSpan {
        final int sequence;final String model;final long started=System.nanoTime();ChatUsage usage;
        ModelSpan(int sequence,String model){this.sequence=sequence;this.model=model;}
    }
    private record ToolSpan(int sequence,String name,long started){}
}
