package org.example.timecoinweb.agent;

import com.sun.net.httpserver.HttpServer;
import org.example.timecoinweb.config.SerializationConfig;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentProtocolTests {
    @Test void nativeToolCallsObserveValidationErrorRepairAndComplete() throws Exception {
        ObjectMapper json=new SerializationConfig().jsonMapper();
        var requests=new CopyOnWriteArrayList<JsonNode>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/custom/chat",exchange->{
            JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
            int round=requests.size();String name=round<3?"validate":"complete";
            Map<String,Object> args=round<3?Map.of("quota",round==1?0:2):Map.of();
            Map<String,Object> message=new LinkedHashMap<>();message.put("role","assistant");message.put("content",null);
            message.put("tool_calls",List.of(Map.of("id","call_"+round,"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args)))));
            byte[] response=json.writeValueAsBytes(Map.of("id","response_"+round,"object","chat.completion","model","protocol-test",
                    "usage",Map.of("prompt_tokens",100*round,"completion_tokens",10,"total_tokens",100*round+10),
                    "choices",List.of(Map.of("index",0,"finish_reason","tool_calls","message",message))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            AiChatService source=new AiChatService();
            ReflectionTestUtils.setField(source,"enabled",true);
            ReflectionTestUtils.setField(source,"apiKey","local-protocol-test-key");
            ReflectionTestUtils.setField(source,"model","protocol-test");
            ReflectionTestUtils.setField(source,"apiUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/custom/chat");
            JdbcTemplate jdbc=mock(JdbcTemplate.class);
            var audit=new CopyOnWriteArrayList<JsonNode>();
            doAnswer(invocation->{audit.add(json.readTree((String)invocation.getArgument(5)));return 1;})
                    .when(jdbc).update(anyString(),any(),any(),any(),any(),any());
            var runtime=new AgentRuntime(source,json,jdbc);AtomicInteger quota=new AtomicInteger();
            JsonNode result=runtime.run(7,UUID.randomUUID().toString(),"校验并完成","需要两个名额",List.of(
                    new AgentToolSpec("validate","检查名额",AgentToolSpec.object(Map.of("quota",Map.of("type","integer")),"quota"),false,args->{
                        int value=args.path("quota").intValue();if(value<1)throw new IllegalArgumentException("quota必须为正整数");quota.set(value);return Map.of("quota",value);
                    }),new AgentToolSpec("complete","完成",AgentToolSpec.object(Map.of()),true,args->{
                        if(quota.get()==0)throw new IllegalArgumentException("先校验");return Map.of("quota",quota.get());
                    })));
            assertEquals(2,result.path("quota").intValue());assertEquals(3,requests.size());
            assertTrue(requests.get(1).path("messages").toString().contains("quota必须为正整数"));
            assertTrue(requests.get(2).path("messages").toString().contains("tool_call_id"));
            String schema=requests.get(0).path("tools").toString();
            assertFalse(schema.contains("actor"));assertFalse(schema.contains("pay"));
            assertFalse(requests.get(0).path("messages").toString().contains("local-protocol-test-key"));
            assertFalse(requests.get(0).path("stream").asBoolean());
            JsonNode summary=audit.stream().filter(e->e.has("usageComplete")).findFirst().orElseThrow();
            assertEquals(600,summary.path("inputTokens").asLong());assertEquals(30,summary.path("outputTokens").asLong());
            assertTrue(summary.path("usageComplete").asBoolean());assertEquals(1,summary.path("toolFailures").asInt());
        } finally {server.stop(0);}
    }
}
