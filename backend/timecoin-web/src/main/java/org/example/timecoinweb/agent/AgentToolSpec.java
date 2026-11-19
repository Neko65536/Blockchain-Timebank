package org.example.timecoinweb.agent;

import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;

/** 业务工具白名单。身份、数据库句柄和权限检查由服务端闭包提供，不进入模型参数。 */
public record AgentToolSpec(String name, String description, Map<String,Object> schema,
                            boolean terminal, Function<JsonNode,Object> execute) {
    public static Map<String,Object> object(Map<String,Object> fields, String... required) {
        return Map.of("type","object","properties",fields,"required",List.of(required),"additionalProperties",false);
    }
    public static Map<String,Object> text() { return Map.of("type","string"); }
}
