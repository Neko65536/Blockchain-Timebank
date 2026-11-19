package org.example.timecoinweb.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.*;
import java.util.*;

/** 仅替换远端模型，真实运行AgentScope循环、工具schema校验和全部业务工具。 */
public final class ScriptedAgentModel implements Model {
    private final JsonNode plan;
    private int step;
    private final ObjectMapper json=new ObjectMapper();
    private ScriptedAgentModel(JsonNode plan){this.plan=plan;}
    public static Model forPlan(JsonNode plan){return new ScriptedAgentModel(plan);}
    @Override public String getModelName(){return "scripted-test";}
    @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
        // 每个调用独立的step；重复请求创建新的模型实例。
        String tool; Map<String,Object> args; int index=step++;
        if(plan.has("reviews")) {
            if(index<plan.path("reviews").size()) {tool="inspect_activity";args=Map.of("activityId",plan.path("reviews").get(index).path("activityId").intValue());}
            else {tool="complete";args=json.convertValue(plan,Map.class);}
        } else if(plan.has("tool")) {
            if(index==0){tool=plan.path("tool").asText();args=json.convertValue(plan,Map.class);args.remove("tool");}
            else {tool="complete";args=Map.of();}
        } else {
            if(index==0){tool="validate_draft";args=Map.of("draft",json.convertValue(plan,Map.class));}
            else {tool="complete";args=Map.of();}
        }
        return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                .content(List.of(new ToolUseBlock(UUID.randomUUID().toString(),tool,args))).finishReason("tool_calls").build());
    }
}
