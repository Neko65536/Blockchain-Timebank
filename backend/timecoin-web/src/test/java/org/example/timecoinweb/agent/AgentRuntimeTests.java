package org.example.timecoinweb.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.example.timecoinweb.config.SerializationConfig;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeTests {
    final ObjectMapper json=new SerializationConfig().jsonMapper();
    AiChatService source; AgentRuntime runtime; JdbcTemplate jdbc;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:runtime"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); source=mock(AiChatService.class);runtime=new AgentRuntime(source,json,jdbc);
    }
    abstract static class TestModel implements Model {public String getModelName(){return "controlled-test";}}
    @Test void existingInputGuardrailsRejectEmergencyWithoutModelOrTools(){
        var guarded=new AgentRuntime(new AiChatService(),json,jdbc);
        AtomicInteger reads=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->guarded.run(1,UUID.randomUUID().toString(),"流程","需要急救",tools("one",reads)));
        assertEquals(0,reads.get());assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit",Integer.class));
    }
    static Flux<ChatResponse> call(String name,Map<String,Object> args){return Flux.just(ChatResponse.builder()
            .content(List.of(new ToolUseBlock(UUID.randomUUID().toString(),name,args))).build());}
    List<AgentToolSpec> tools(String value,AtomicInteger reads){
        return List.of(new AgentToolSpec("read","read",AgentToolSpec.object(Map.of()),false,args->{reads.incrementAndGet();return value;}),
                new AgentToolSpec("complete","complete",AgentToolSpec.object(Map.of()),true,args->{if(reads.get()==0)throw new IllegalArgumentException("先读取");return value;}));
    }
    @Test void observesToolResultThenCompletesAndAuditsWithoutRawContent(){
        AtomicInteger rounds=new AtomicInteger(),reads=new AtomicInteger();
        when(source.reactModel()).thenReturn(new TestModel(){
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
                if(rounds.getAndIncrement()==0)return call("read",Map.of());
                assertTrue(messages.stream().anyMatch(m->m.hasContentBlocks(ToolResultBlock.class)));
                return call("complete",Map.of());
            }});
        String id=UUID.randomUUID().toString();
        assertEquals("private-business-data",runtime.run(1,id,"流程","private-question",tools("private-business-data",reads)).asText());
        assertEquals(2,rounds.get());assertEquals(1,reads.get());
        String audit=jdbc.queryForList("select * from agent_audit where task_id=?",id).toString();
        assertTrue(audit.contains("REACT_COMPLETE"));assertFalse(audit.contains("private-business-data"));assertFalse(audit.contains("private-question"));
        var summary=event("REACT_COMPLETE");
        assertEquals(id,summary.path("traceId").asText());
        assertEquals(2,summary.path("toolCalls").asInt());assertEquals(0,summary.path("toolFailures").asInt());
        assertEquals(2,summary.path("usageMissingCalls").asInt());assertFalse(summary.path("usageComplete").asBoolean());
        assertEquals(2,events("MODEL_CALL").size());assertEquals(2,events("TOOL_CALL").size());
    }
    @Test void forgedIdentityAndUnregisteredPaymentNeverReachBusinessTool(){
        AtomicInteger reads=new AtomicInteger(),rounds=new AtomicInteger();
        when(source.reactModel()).thenReturn(new TestModel(){public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){
            return rounds.getAndIncrement()==0?call("read",Map.of("actor",999)):call("pay",Map.of("amount",1));
        }});
        assertThrows(Exception.class,()->runtime.run(1,UUID.randomUUID().toString(),"流程","转账",tools("one",reads)));
        assertEquals(0,reads.get());assertTrue(rounds.get()<=6);
        assertTrue(events("TOOL_CALL").stream().anyMatch(e->e.path("tool").asText().equals("UNREGISTERED") && e.path("status").asText().equals("FAILED")));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit where tool='REACT_COMPLETE'",Integer.class));
    }
    @Test void repeatedReadCannotExhaustUnboundedModelOrToolBudget(){
        ReflectionTestUtils.setField(runtime,"maxToolCalls",2);AtomicInteger reads=new AtomicInteger(),rounds=new AtomicInteger();
        when(source.reactModel()).thenReturn(new TestModel(){public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){rounds.incrementAndGet();return call("read",Map.of());}});
        assertThrows(Exception.class,()->runtime.run(1,UUID.randomUUID().toString(),"流程","重复",tools("one",reads)));
        assertEquals(2,reads.get());assertTrue(rounds.get()<=6);
    }
    @Test void internalFailureStopsFurtherModelCallsAndDoesNotLeakDatabaseDetails(){
        AtomicInteger rounds=new AtomicInteger();
        when(source.reactModel()).thenReturn(new TestModel(){public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){
            rounds.incrementAndGet();return call("read",Map.of());
        }});
        assertThrows(IllegalStateException.class,()->runtime.run(1,UUID.randomUUID().toString(),"流程","查询",List.of(
                new AgentToolSpec("read","读取",AgentToolSpec.object(Map.of()),false,args->{throw new IllegalStateException("private-database-detail");}))));
        assertEquals(1,rounds.get());
        assertFalse(jdbc.queryForList("select detail from agent_audit").toString().contains("private-database-detail"));
        assertEquals("FAILED",event("TOOL_CALL").path("status").asText());
    }
    @Test void timeoutCancelsModelSubscriptionAndProducesNoCompletion(){
        ReflectionTestUtils.setField(runtime,"timeoutSeconds",1);AtomicBoolean cancelled=new AtomicBoolean();
        when(source.reactModel()).thenReturn(new TestModel(){public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return Flux.<ChatResponse>never().doOnCancel(()->cancelled.set(true));}});
        var error=assertThrows(ResponseStatusException.class,()->runtime.run(1,UUID.randomUUID().toString(),"流程","等待",tools("one",new AtomicInteger())));
        assertEquals(504,error.getStatusCode().value());assertTrue(cancelled.get());
        assertEquals("CANCELLED",event("MODEL_CALL").path("status").asText());
        assertEquals(1,event("REACT_FAILED").path("usageMissingCalls").asInt());
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit where tool='REACT_COMPLETE'",Integer.class));
    }
    @Test void requestInterruptionCancelsTheActiveModel() throws Exception {
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        when(source.reactModel()).thenReturn(new TestModel(){public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return Flux.<ChatResponse>never().doOnSubscribe(s->entered.countDown()).doOnCancel(cancelled::countDown);}});
        try(var pool=Executors.newSingleThreadExecutor()){
            var work=pool.submit(()->runtime.run(1,UUID.randomUUID().toString(),"流程","取消",tools("one",new AtomicInteger())));
            assertTrue(entered.await(3,TimeUnit.SECONDS));work.cancel(true);assertTrue(cancelled.await(3,TimeUnit.SECONDS));
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit where tool='REACT_COMPLETE'",Integer.class));
    }
    @Test void simultaneousUsersHaveIndependentToolsAndContext() throws Exception {
        when(source.reactModel()).thenAnswer(invocation->new TestModel(){int step;
            public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return call(step++==0?"read":"complete",Map.of());}});
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->runtime.run(1,UUID.randomUUID().toString(),"流程","甲",tools("A",new AtomicInteger())).asText());
            var b=pool.submit(()->runtime.run(2,UUID.randomUUID().toString(),"流程","乙",tools("B",new AtomicInteger())).asText());
            assertEquals("A",a.get(5,TimeUnit.SECONDS));assertEquals("B",b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(2,jdbc.queryForObject("select count(distinct actor_id) from agent_audit where tool='REACT_COMPLETE'",Integer.class));
        assertEquals(2,events("REACT_COMPLETE").stream().map(e->e.path("traceId").asText()).distinct().count());
    }
    List<tools.jackson.databind.JsonNode> events(String name){
        return jdbc.queryForList("select detail from agent_audit where tool=?",String.class,name).stream().map(json::readTree).toList();
    }
    tools.jackson.databind.JsonNode event(String name){var events=events(name);assertEquals(1,events.size());return events.get(0);}

    @Test void independentTraceRowStoresHashesAndCountersWithoutRawContent() {
        AtomicInteger reads=new AtomicInteger();AtomicInteger rounds=new AtomicInteger();
        when(source.reactModel()).thenReturn(new TestModel(){
            public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){
                return rounds.getAndIncrement()==0?call("read",Map.of()):call("complete",Map.of());}});
        String id=UUID.randomUUID().toString();
        runtime.run(1,id,"流程","private-question",tools("private-business-data",reads));
        var traces=jdbc.queryForList("select * from agent_trace where trace_id=?",id);
        assertEquals(1,traces.size());
        var trace=traces.get(0);
        assertEquals("COMPLETE",trace.get("status"));
        assertNotNull(trace.get("prompt_hash"));assertNotNull(trace.get("result_hash"));
        assertEquals(2,((Number)trace.get("tool_calls")).intValue());
        assertEquals("PASS",trace.get("guardrail_result"));
        String all=trace.toString();
        assertFalse(all.contains("private-question"));assertFalse(all.contains("private-business-data"));
    }

    @Test void cumulativeUsageIsCountedOncePerModelCallAndSynchronousFailureIsMeasured(){
        String id=UUID.randomUUID().toString();var telemetry=new AgentTelemetry(id,1,jdbc,json,List.of());
        telemetry.observeModel("test",()->Flux.just(
                ChatResponse.builder().usage(new ChatUsage(10,2,0)).build(),
                ChatResponse.builder().usage(new ChatUsage(10,5,0)).build())).blockLast();
        telemetry.observeModel("test",()->Flux.just(ChatResponse.builder().usage(new ChatUsage(20,3,0)).build())).blockLast();
        assertThrows(IllegalStateException.class,()->telemetry.observeModel("test",()->{throw new IllegalStateException("private-provider-error");}).blockLast());
        telemetry.finish(false);
        var summary=event("REACT_FAILED");assertEquals(30,summary.path("inputTokens").asLong());
        assertEquals(8,summary.path("outputTokens").asLong());assertEquals(38,summary.path("totalTokens").asLong());
        assertEquals(3,summary.path("modelCalls").asInt());assertEquals(2,summary.path("usageReportedCalls").asInt());
        assertEquals(1,summary.path("usageMissingCalls").asInt());assertFalse(summary.path("usageComplete").asBoolean());
        assertEquals(3,events("MODEL_CALL").size());
        assertTrue(events("MODEL_CALL").stream().allMatch(e->e.path("durationMs").asLong()>=0));
        assertFalse(jdbc.queryForList("select detail from agent_audit").toString().contains("private-provider-error"));
    }

    @Test void deniedInterruptedAndUnfinishedToolsAreNotSuccessful(){
        var telemetry=new AgentTelemetry(UUID.randomUUID().toString(),1,jdbc,json,tools("value",new AtomicInteger()));
        var toolkit=new io.agentscope.core.tool.Toolkit();
        var hookAgent=mock(io.agentscope.core.agent.Agent.class);
        for(var state:List.of(ToolResultState.DENIED,ToolResultState.INTERRUPTED,ToolResultState.RUNNING)){
            var use=new ToolUseBlock(UUID.randomUUID().toString(),"read",Map.of());
            telemetry.onEvent(new io.agentscope.core.hook.PreActingEvent(hookAgent,toolkit,use)).block();
            if(state!=ToolResultState.RUNNING)
                telemetry.onEvent(new io.agentscope.core.hook.PostActingEvent(hookAgent,toolkit,use,ToolResultBlock.text("private").withState(state))).block();
        }
        telemetry.finish(false);
        assertEquals(3,event("REACT_FAILED").path("toolFailures").asInt());
        assertEquals(2,events("TOOL_CALL").stream().filter(e->"CANCELLED".equals(e.path("status").asText())).count());
        assertEquals(1,events("TOOL_CALL").stream().filter(e->"FAILED".equals(e.path("status").asText())).count());
    }
}
