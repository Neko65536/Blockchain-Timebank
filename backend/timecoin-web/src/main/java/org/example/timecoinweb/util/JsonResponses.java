package org.example.timecoinweb.util;

import org.example.pojo.Result;
import tools.jackson.databind.json.JsonMapper;

/** N10：替换拦截器中高危 fastjson 1.2.76 的依赖，统一用 Jackson3 序列化简单错误响应。 */
public final class JsonResponses {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonResponses() {}

    /** 序列化 Result.error(msg)；msg 可能含引号，交由 Jackson 正确转义。 */
    public static String error(String message) {
        Result r = Result.error(message);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", r.getCode());
        body.put("msg", r.getMsg());
        body.put("data", null);
        return MAPPER.writeValueAsString(body);
    }
}
