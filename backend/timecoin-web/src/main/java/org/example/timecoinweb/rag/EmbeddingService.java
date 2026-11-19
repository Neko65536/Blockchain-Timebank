package org.example.timecoinweb.rag;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

/** local-hash仅用于离线词面相似度验证；remote调用真实Embedding接口，不静默降级。 */
@Service
public class EmbeddingService {
    private final ObjectMapper json;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(20)).callTimeout(Duration.ofSeconds(25)).build();
    @Value("${ai.rag.embedding-mode:remote}") private String mode;
    @Value("${ai.rag.embedding-url:}") private String url;
    @Value("${ai.rag.embedding-key:}") private String apiKey;
    @Value("${ai.rag.embedding-model:text-embedding-v3}") private String model;
    @Value("${ai.rag.embedding-dimensions:512}") private int dimensions;
    public EmbeddingService(ObjectMapper json) { this.json = json; }

    public String key() {
        return Hash.sha3String("rag-embedding-v1:" + mode + ":" + url + ":" + model + ":" + dimensions);
    }
    public List<double[]> embed(List<String> texts) {
        if (texts.isEmpty() || texts.size() > 16 || dimensions < 32 || dimensions > 4096)
            throw new IllegalArgumentException("Embedding批次或维度不合法");
        if ("local-hash".equals(mode)) {
            List<double[]> vectors = new ArrayList<>();
            for (String text : texts) {
                double[] vector = new double[dimensions];
                int[] points = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "").codePoints().toArray();
                for (int i = 0; i < points.length; i++) {
                    if (!Character.isLetterOrDigit(points[i])) continue;
                    int hash = i + 1 < points.length ? 31 * points[i] + points[i + 1] : points[i];
                    vector[Math.floorMod(hash, dimensions)]++;
                }
                vectors.add(normalize(vector));
            }
            return vectors;
        }
        if (!"remote".equals(mode) || url.isBlank() || apiKey.isBlank()) throw new IllegalStateException("请配置Embedding模型端点和密钥");
        try {
            String body = json.writeValueAsString(Map.of("model", model, "input", texts, "dimensions", dimensions, "encoding_format", "float"));
            Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8"))).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Embedding HTTP " + response.code());
                JsonNode data = json.readTree(response.body().string()).path("data");
                if (!data.isArray() || data.size() != texts.size()) throw new IOException("Embedding返回数量不匹配");
                double[][] result = new double[texts.size()][];
                for (JsonNode item : data) {
                    if (!item.path("index").isIntegralNumber()) throw new IOException("Embedding索引缺失");
                    int index = item.path("index").intValue();
                    if (index < 0 || index >= result.length || result[index] != null || !item.path("embedding").isArray() || item.path("embedding").size() != dimensions)
                        throw new IOException("Embedding索引或维度不匹配");
                    double[] vector = new double[dimensions];
                    for (int i = 0; i < dimensions; i++) {
                        if (!item.path("embedding").get(i).isNumber()) throw new IOException("Embedding值非数值");
                        vector[i] = item.path("embedding").get(i).doubleValue();
                    }
                    result[index] = normalize(vector);
                }
                return Arrays.asList(result);
            }
        } catch (IOException e) { throw new IllegalStateException("Embedding服务请求失败", e); }
    }
    static double[] normalize(double[] vector) {
        double length = 0;
        for (double v : vector) { if (!Double.isFinite(v)) throw new IllegalArgumentException("向量包含非有限值"); length += v * v; }
        if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("向量为空或无效");
        length = Math.sqrt(length);
        for (int i = 0; i < vector.length; i++) vector[i] /= length;
        return vector;
    }
}
