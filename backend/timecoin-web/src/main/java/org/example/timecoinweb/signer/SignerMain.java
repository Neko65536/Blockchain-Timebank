package org.example.timecoinweb.signer;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** 使用PropertiesLauncher单独启动，不加载Spring业务服务，不连接数据库或广播交易。 */
public final class SignerMain {
    private static String required(String name) {String value=System.getenv(name);if(value==null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量："+name);return value;}
    public static void main(String[] args) throws Exception {
        // N11：支持多 token 轮换——SIGNER_TOKENS="新token,旧token"，逗号分隔；兼容单 token 的 SIGNER_TOKEN。
        // 首个 token 为 active，其余为轮换窗口内的旧 token；全部至少 32 字节。
        String multi=System.getenv("SIGNER_TOKENS");
        List<byte[]> tokens=new ArrayList<>();
        if(multi!=null && !multi.isBlank()) {
            for(String part:multi.split(",")) {
                byte[] t=part.trim().getBytes(StandardCharsets.UTF_8);
                if(t.length<32) throw new IllegalArgumentException("SIGNER_TOKENS每个token至少32字节");
                tokens.add(t);
            }
        } else {
            byte[] token=required("SIGNER_TOKEN").getBytes(StandardCharsets.UTF_8);
            if(token.length<32) throw new IllegalArgumentException("SIGNER_TOKEN至少32字节");
            tokens.add(token);
        }
        final List<byte[]> acceptedTokens=List.copyOf(tokens);
        SignerEngine engine=new SignerEngine(Files.readString(Path.of(required("SIGNER_KEY_FILE"))).trim(),required("SIGNER_CHAIN_ID"),required("SIGNER_GROUP_ID"),required("SIGNER_CONTRACT"),Set.of(required("SIGNER_SELECTORS").split(",")));
        String host=System.getenv().getOrDefault("SIGNER_HOST","127.0.0.1");
        InetSocketAddress address=new InetSocketAddress(host,Integer.parseInt(System.getenv().getOrDefault("SIGNER_PORT","8443")));
        HttpServer server;
        String tls=System.getenv("SIGNER_TLS_KEYSTORE");
        if(tls!=null && !tls.isBlank()) {
            char[] password=required("SIGNER_TLS_PASSWORD").toCharArray();KeyStore store=KeyStore.getInstance("PKCS12");
            try(java.io.InputStream stream=Files.newInputStream(Path.of(tls))) {store.load(stream,password);}
            KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);
            SSLContext context=SSLContext.getInstance("TLS");context.init(keys.getKeyManagers(),null,null);
            HttpsServer secure=HttpsServer.create(address,32);secure.setHttpsConfigurator(new HttpsConfigurator(context));server=secure;
        } else {
            if(!address.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("非回环地址必须启用TLS");
            server=HttpServer.create(address,32);
        }
        ObjectMapper json=new ObjectMapper();Semaphore capacity=new Semaphore(4);
        server.createContext("/v1/sign",exchange->{
            int code=200;byte[] body;
            boolean acquired=false;
            try {
                if(!"POST".equals(exchange.getRequestMethod()) || !"/v1/sign".equals(exchange.getRequestURI().getPath())) {code=405;throw new IllegalArgumentException();}
                String auth=exchange.getRequestHeaders().getFirst("Authorization");
                int matched=-1;
                if(auth!=null) {
                    byte[] authBytes=auth.getBytes(StandardCharsets.UTF_8);
                    for(int i=0;i<acceptedTokens.size();i++) {
                        byte[] expected=("Bearer "+new String(acceptedTokens.get(i),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                        if(MessageDigest.isEqual(expected,authBytes)) {matched=i;break;}
                    }
                }
                if(matched<0) {
                    // N11：鉴权失败审计——只记来源与摘要，不记 token 明文。
                    System.out.println("SIGN_AUDIT rejected remote="+exchange.getRemoteAddress().getAddress().getHostAddress()+" reason=BAD_TOKEN");
                    code=401;throw new IllegalArgumentException();
                }
                acquired=capacity.tryAcquire();if(!acquired) {code=503;throw new IllegalArgumentException();}
                byte[] input=exchange.getRequestBody().readNBytes(32769);if(input.length>32768) {code=413;throw new IllegalArgumentException();}
                body=json.writeValueAsBytes(engine.sign(json.readTree(input)));
                // N11：成功审计——记录调用方来源与 token 代际（active=0），便于轮换期识别旧凭证使用。
                System.out.println("SIGN_AUDIT ok remote="+exchange.getRemoteAddress().getAddress().getHostAddress()+" tokenGeneration="+matched);
            } catch(Exception invalid) {if(code==200) code=400;body="{\"error\":\"signing request rejected\"}".getBytes(StandardCharsets.UTF_8);}
            finally {if(acquired) capacity.release();}
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,body.length);
            try(java.io.OutputStream output=exchange.getResponseBody()) {output.write(body);}finally {exchange.close();}
        });
        ThreadPoolExecutor pool=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());server.setExecutor(pool);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);pool.shutdownNow();}));server.start();
        System.out.println("Signer started; policy loaded; private key stays in this process.");
    }
}
