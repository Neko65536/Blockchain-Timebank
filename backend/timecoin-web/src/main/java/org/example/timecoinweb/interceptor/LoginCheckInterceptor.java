package org.example.timecoinweb.interceptor;


import com.alibaba.fastjson.JSONObject;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Result;
import org.example.utils.JwtUtils;
import org.example.pojo.User;
import org.example.timecoinweb.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoginCheckInterceptor implements HandlerInterceptor {
    private final UserMapper userMapper;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler) throws Exception{
        // CORS 由 WebMvcConfig 统一配置；此处曾使用 * + Allow-Credentials 组合，会导致浏览器预检失败

        // 预检请求无 token，直接放行（由 Cors 配置补全响应头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if (("POST".equals(method) && ("/login".equals(path) || "/register".equals(path)))
                || ("GET".equals(method) && (path.startsWith("/image/") || "/dashboard/notices".equals(path)
                    || "/agreement/current".equals(path)))) {
            return true;
        }
        String jwt = request.getHeader("token");
        if (!StringUtils.hasText(jwt)) {
            return reject(response, 401, "NOT_LOGIN");
        }
        Claims claims;
        try {
            claims = JwtUtils.parseJWT(jwt);
        } catch (RuntimeException e) {
            return reject(response, 401, "NOT_LOGIN");
        }
        Object id = claims.get("id");
        Object roleClaim = claims.get("role");
        if (!(id instanceof Integer) || !(roleClaim instanceof Integer)) return reject(response, 401, "NOT_LOGIN");
        int role = (Integer) roleClaim;
        User user = userMapper.selectById((Integer) id);
        if (user == null || user.getRole() == null || user.getRole() != role || role < 1 || role > 3) {
            return reject(response, 401, "NOT_LOGIN");
        }
        // J4：冻结账号即时失效——即使 token 未过期也拒绝，实现「冻结即停权」
        if (user.getStatus() != null && user.getStatus() == 0) {
            return reject(response, 401, "ACCOUNT_FROZEN");
        }
        boolean allowed = under(path, "/info")
                || (role == 3 && (under(path, "/administrator") || under(path, "/users/administrator") || "/dashboard/stats".equals(path)))
                || (role == 1 && (under(path, "/users/old") || under(path, "/users/volold")))
                || (role == 2 && (under(path, "/users/vol") || under(path, "/users/volold")));
        if (allowed) request.setAttribute("authenticatedUserId", id);
        return allowed || reject(response, 403, "ACCESS_RESTRICTED");
    }

    private boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONObject.toJSONString(Result.error(message)));
        return false;
    }
}
