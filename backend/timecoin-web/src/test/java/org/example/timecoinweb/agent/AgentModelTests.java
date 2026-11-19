package org.example.timecoinweb.agent;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentModelTests {
    @Test void unconfiguredModelDoesNotPretendToExecuteTools() {
        AiChatService model = new AiChatService();
        assertThrows(IllegalArgumentException.class, () -> model.planAgentTool("{}", ""));
        assertThrows(IllegalStateException.class, () -> model.planAgentTool("{}", "陪同购物"));
    }
    @Test void remoteOutputMustBeOneJsonObject() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>("{\"tool\":\"draft\"}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = new ObjectMapper().writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",reply.get()))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            AiChatService model = new AiChatService();
            ReflectionTestUtils.setField(model,"enabled",true);
            ReflectionTestUtils.setField(model,"apiKey","local-test-key");
            ReflectionTestUtils.setField(model,"model","test-model");
            ReflectionTestUtils.setField(model,"apiUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/chat");
            assertEquals("draft",model.planAgentTool("{tool:string}","陪同购物").path("tool").asText());
            for (String invalid : List.of("[]","{} {}","```json\n{}\n```")) {
                reply.set(invalid);
                assertThrows(Exception.class, () -> model.planAgentTool("{tool:string}","陪同购物"));
            }
        } finally { server.stop(0); }
    }
}
