package org.example.timecoinweb.interceptor;

import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.web3j.crypto.Hash;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class RedisRateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate redis;
    @Value("${app.redis-enabled:false}") private boolean enabled;
    @Value("${app.rate-limit.write-limit:30}") private int writeLimit;
    @Value("${app.rate-limit.auth-limit:10}") private int authLimit;
    /** J6：重量 GET（如链上概览逐用户 RPC + 全链扫日志）纳入限流，避免单请求打穿节点 */
    @Value("${app.rate-limit.heavy-get-enabled:true}") private boolean heavyGetEnabled;
    @Value("${app.rate-limit.heavy-get-limit:20}") private int heavyGetLimit;
    /** 重量 GET 路径前缀（逗号分隔） */
    @Value("${app.rate-limit.heavy-get-paths:/administrator/chain/overview,/administrator/chain/settlements,/administrator/chain/transactions,/administrator/chain/reconcile/logs}")
    private String heavyGetPaths;
    private static final DefaultRedisScript<Long> WINDOW = new DefaultRedisScript<>();
    static {
        WINDOW.setLocation(new org.springframework.core.io.ClassPathResource("rate-limit-sliding.lua"));
        WINDOW.setResultType(Long.class);
    }
    @jakarta.annotation.PostConstruct
    public void validateLimits() {
        if (writeLimit <= 0 || authLimit <= 0) throw new IllegalArgumentException("限流额度必须大于0");
        if (heavyGetEnabled && heavyGetLimit <= 0) throw new IllegalArgumentException("重量GET限流额度必须大于0");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) return true;
        if (!enabled) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean write = java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
        // J6：匹配重量 GET（链上概览/交易列表等），按用户独立限流
        boolean heavyGet = heavyGetEnabled && "GET".equals(request.getMethod()) && isHeavyGet(path);
        if (!write && !heavyGet) return true;
        boolean auth = "/login".equals(path) || "/register".equals(path);
        Object user = request.getAttribute("authenticatedUserId");
        if (!auth && user == null) return true;
        // 不直接信任客户端X-Forwarded-For；反向代理须在可信边界配置真实地址。
        String subject = auth ? "ip:" + Hash.sha3String(request.getRemoteAddr()) : "user:" + user;
        String bucket = auth ? "auth:" : heavyGet ? "heavy:" : "write:";
        int limit = auth ? authLimit : heavyGet ? heavyGetLimit : writeLimit;
        try {
            // 版本化键避免与旧固定窗口的String类型冲突；UUID避免同毫秒请求被ZSET合并。
            Long waitMs = redis.execute(WINDOW, Collections.singletonList("timebank:rate:v2:" + bucket + subject),
                    "60000", Integer.toString(limit), java.util.UUID.randomUUID().toString());
            if (waitMs == null || waitMs < 0) return reject(response, 503, "限流服务暂不可用", 3);
            return waitMs == 0 || reject(response, 429, "请求过于频繁，请稍后重试", (waitMs + 999) / 1000);
        } catch (DataAccessException unavailable) { return reject(response, 503, "限流服务暂不可用，请稍后重试", 3); }
    }

    private boolean isHeavyGet(String path) {
        if (heavyGetPaths == null || heavyGetPaths.isBlank()) return false;
        for (String prefix : heavyGetPaths.split(",")) {
            String p = prefix.trim();
            if (!p.isEmpty() && path.equals(p)) return true;
        }
        return false;
    }
    private boolean reject(HttpServletResponse response, int code, String message, long retrySeconds) throws Exception {
        response.setStatus(code); response.setHeader("Retry-After", Long.toString(retrySeconds));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONObject.toJSONString(Result.error(message)));
        return false;
    }
}
