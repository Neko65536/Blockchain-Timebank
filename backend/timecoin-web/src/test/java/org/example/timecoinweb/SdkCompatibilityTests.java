package org.example.timecoinweb;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SdkCompatibilityTests {
    @Test void legacyWeb3jTransportWorksWithSharedOkHttpOnJava21() throws Exception {
        assertEquals(1,java.util.Collections.list(getClass().getClassLoader().getResources("okhttp3/OkHttpClient.class")).size(),
                "不能同时装入OkHttp4和OkHttp5的同名类");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] reply="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"timebank-test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);
            try(var output=exchange.getResponseBody()){output.write(reply);}
        });
        server.start();
        var transport=new HttpService("http://127.0.0.1:"+server.getAddress().getPort());
        try {
            var client=Web3j.build(transport);
            try { assertEquals("timebank-test",client.web3ClientVersion().send().getWeb3ClientVersion()); }
            finally {client.shutdown();}
        } finally {server.stop(0);}
    }
}
