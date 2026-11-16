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
    private static final DefaultRedisScript<Long> WINDOW = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n", Long.class);

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
            Long count = redis.execute(WINDOW, Collections.singletonList("timebank:rate:" + (auth ? "auth:" : "write:") + subject), "60");
            if (count == null) return reject(response, 503, "限流服务暂不可用");
            return count <= (auth ? authLimit : writeLimit) || reject(response, 429, "请求过于频繁，请稍后重试");
        } catch (DataAccessException unavailable) { return reject(response, 503, "限流服务暂不可用，请稍后重试"); }
    }
    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code); response.setHeader("Retry-After", code == 429 ? "60" : "3");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONObject.toJSONString(Result.error(message)));
        return false;
    }
}
