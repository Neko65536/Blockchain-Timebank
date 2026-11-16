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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class RedisRateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate redis;
    @Value("${app.redis-enabled:false}") private boolean enabled;
    @Value("${app.rate-limit.write-limit:30}") private int writeLimit;
    @Value("${app.rate-limit.auth-limit:10}") private int authLimit;
    private static final DefaultRedisScript<Long> WINDOW = new DefaultRedisScript<>();
    static {
        WINDOW.setLocation(new org.springframework.core.io.ClassPathResource("rate-limit-sliding.lua"));
        WINDOW.setResultType(Long.class);
    }
    @javax.annotation.PostConstruct
    public void validateLimits() {
        if (writeLimit <= 0 || authLimit <= 0) throw new IllegalArgumentException("限流额度必须大于0");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getDispatcherType() == javax.servlet.DispatcherType.ASYNC) return true;
        if (!enabled || !java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean auth = "/login".equals(path) || "/register".equals(path);
        Object user = request.getAttribute("authenticatedUserId");
        if (!auth && user == null) return true;
        // 不直接信任客户端X-Forwarded-For；反向代理须在可信边界配置真实地址。
        String subject = auth ? "ip:" + Hash.sha3String(request.getRemoteAddr()) : "user:" + user;
        try {
            // 版本化键避免与旧固定窗口的String类型冲突；UUID避免同毫秒请求被ZSET合并。
            Long waitMs = redis.execute(WINDOW, Collections.singletonList("timebank:rate:v2:" + (auth ? "auth:" : "write:") + subject),
                    "60000", Integer.toString(auth ? authLimit : writeLimit), java.util.UUID.randomUUID().toString());
            if (waitMs == null || waitMs < 0) return reject(response, 503, "限流服务暂不可用", 3);
            return waitMs == 0 || reject(response, 429, "请求过于频繁，请稍后重试", (waitMs + 999) / 1000);
        } catch (DataAccessException unavailable) { return reject(response, 503, "限流服务暂不可用，请稍后重试", 3); }
    }
    private boolean reject(HttpServletResponse response, int code, String message, long retrySeconds) throws Exception {
        response.setStatus(code); response.setHeader("Retry-After", Long.toString(retrySeconds));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONObject.toJSONString(Result.error(message)));
        return false;
    }
}
