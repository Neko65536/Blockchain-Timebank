package org.example.timecoinweb.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** 业务进程只持有调用凭证；TLS使用JVM信任库，不跳过服务端证书验证。 */
public final class RemoteSigner {
    private final URI endpoint;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public RemoteSigner(String url, String token) {
        this.endpoint = URI.create(url);
        String host = endpoint.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !"/v1/sign".equals(endpoint.getPath())
                || !("https".equals(endpoint.getScheme()) || (loopback && "http".equals(endpoint.getScheme()))))
            throw new IllegalArgumentException("signer必须使用HTTPS；仅IP回环地址允许HTTP");
        if (token == null || token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("signer调用凭证无效");
        this.token = token;
    }

    public JsonNode sign(String chain, String group, String contract, String input, long blockLimit) throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("chainId", chain, "groupId", group, "to", contract,
                "input", input, "blockLimit", blockLimit));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(65537);
            if (response.statusCode() != 200 || bytes.length > 65536) throw new IllegalStateException("独立签名服务拒绝请求或响应超限");
            JsonNode result = json.readTree(bytes);
            if (result == null || !result.path("txHash").asText().matches("0x[0-9a-fA-F]{64}")
                    || !result.path("rawTx").asText().matches("(0x)?[0-9a-fA-F]{2,60000}")
                    || !result.path("signer").asText().matches("0x[0-9a-fA-F]{40}"))
                throw new IllegalStateException("独立签名服务响应不完整");
            return result;
        }
    }
}
