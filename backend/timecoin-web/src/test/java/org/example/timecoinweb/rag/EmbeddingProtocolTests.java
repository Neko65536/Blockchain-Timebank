package org.example.timecoinweb.rag;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingProtocolTests {
    HttpServer server;EmbeddingService embedding;ObjectMapper json=new ObjectMapper();
    AtomicReference<String> response=new AtomicReference<>(), request=new AtomicReference<>(), authorization=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embeddings",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();embedding=new EmbeddingService(json);
        ReflectionTestUtils.setField(embedding,"mode","remote");ReflectionTestUtils.setField(embedding,"url","http://127.0.0.1:"+server.getAddress().getPort()+"/embeddings");
        ReflectionTestUtils.setField(embedding,"apiKey","protocol-test-only");ReflectionTestUtils.setField(embedding,"model","test");ReflectionTestUtils.setField(embedding,"dimensions",32);
    }
    @AfterEach void close() { server.stop(0); }
    Map<String,Object> item(int index,int dimension) { double[] vector=new double[dimension];vector[0]=1;return Map.of("index",index,"embedding",vector); }
    @Test void remoteProtocolReordersAndNormalizes() throws Exception {
        response.set(json.writeValueAsString(Map.of("data",List.of(item(1,32),item(0,32)))));
        assertEquals(2,embedding.embed(List.of("第一段","第二段")).size());
        assertEquals("Bearer protocol-test-only",authorization.get());assertEquals(32,json.readTree(request.get()).path("dimensions").intValue());
    }
    @Test void malformedVectorsFailWithoutLocalFallback() throws Exception {
        for(List<?> data:List.of(List.of(item(0,31)),List.of(item(0,32),item(0,32)),List.of())) {
            response.set(json.writeValueAsString(Map.of("data",data)));assertThrows(IllegalStateException.class,()->embedding.embed(List.of("知识")));
        }
    }
}
