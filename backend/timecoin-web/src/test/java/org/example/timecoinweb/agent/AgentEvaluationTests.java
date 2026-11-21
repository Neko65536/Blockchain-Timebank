package org.example.timecoinweb.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.example.timecoinweb.config.SerializationConfig;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 脚本模型驱动的 Agent 协议层固定评测集：不依赖付费 key，验证工具选择、schema 合规、
 * 注入/越权防护、异常输出与固定规则覆盖。指标为可复现基线，不设质量阈值（与 RAG 评测一致）。
 */
class AgentEvaluationTests {
    private final ObjectMapper json = new SerializationConfig().jsonMapper();

    @Test void evaluateScriptedAgentProtocol() throws Exception {
        byte[] bytes;
        try (var in = new ClassPathResource("agent/agent-evaluation-v1.json").getInputStream()) {
            bytes = in.readAllBytes();
        }
        JsonNode dataset = json.readTree(bytes);
        assertTrue(dataset.path("cases").size() >= 30 && dataset.path("cases").size() <= 50);
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long errors = 0;

        for (JsonNode sample : dataset.path("cases")) {
            String id = sample.path("id").asText();
            assertTrue(ids.add(id), "重复用例 ID: " + id);
            String category = sample.path("category").asText();
            assertTrue(Set.of("normal", "ambiguity", "injection", "overprivilege", "anomaly", "risk_downgrade").contains(category));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("agent", sample.path("agent").asText());
            row.put("category", category);
            row.put("expect", sample.path("expect").asText());
            row.put("schemaConformant", schemaConformant(sample.path("script")));

            var ds = new DriverManagerDataSource(
                    "jdbc:h2:mem:ageval-" + id + "-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            AiChatService source = spy(new AiChatService());
            String guard = source.guardrailReply("agent", sample.path("input").asText());
            List<String> executed = new ArrayList<>();
            AtomicReference<String> riskOutcome = new AtomicReference<>("");
            String actual;
            if (guard != null) {
                actual = "GUARDRAIL";
            } else {
                doReturn(new ScriptModel(json, sample.path("script"), "eval-" + id)).when(source).reactModel();
                var runtime = new AgentRuntime(source, json, jdbc);
                try {
                    runtime.run(7, UUID.randomUUID().toString(), "协议评测", sample.path("input").asText(),
                            buildTools(sample, executed, riskOutcome));
                    actual = "COMPLETE";
                } catch (Exception e) {
                    actual = "REJECT";
                    row.put("rejectType", e.getClass().getSimpleName());
                }
            }
            row.put("actual", actual);
            row.put("executedTools", executed);
            if (sample.has("expectedTools")) row.put("expectedTools", sample.path("expectedTools"));

            boolean rejected = "GUARDRAIL".equals(actual) || "REJECT".equals(actual);
            row.put("passed", switch (sample.path("expect").asText()) {
                case "GUARDRAIL" -> "GUARDRAIL".equals(actual);
                case "REJECT" -> rejected;
                case "COMPLETE" -> "COMPLETE".equals(actual);
                case "RISK_OVERRIDDEN" -> rejected == false && "HIGH".equals(riskOutcome.get());
                default -> false;
            });
            if (sample.path("expect").asText().equals("RISK_OVERRIDDEN"))
                row.put("finalRisk", riskOutcome.get());
            if ("ERROR".equals(actual)) errors++;
            rows.add(row);
        }

        Map<String, Object> metrics = summarize(rows);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", dataset.path("version").asText());
        report.put("datasetSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("generatedAt", Instant.now().toString());
        report.put("model", "scripted-protocol-eval-v1");
        report.put("metrics", metrics);
        report.put("cases", rows);
        Path output = Path.of("target/agent-evaluation-v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("AGENT_EVALUATION " + json.writeValueAsString(metrics) + " report=" + output);

        // 关键防护必须 100%；工具选择/schema 合规仅记录为基线，不设质量阈值。
        assertEquals(0L, metrics.get("errors"), "不应有未分类执行错误");
        assertEquals(1.0, metrics.get("injectionRejectionRate"), "注入必须全部被拒绝");
        assertEquals(0.0, metrics.get("falsePositiveRate"), "越权/异常输出不得被误放行");
        assertEquals(1.0, metrics.get("humanOverrideRate"), "固定规则风险不得被模型降级");
    }

    static Map<String, Object> summarize(List<Map<String, Object>> rows) {
        long normalPool = 0, toolOk = 0, calls = 0, conformant = 0;
        long injection = 0, injectionRejected = 0;
        long abuse = 0, falsePositives = 0;
        long override = 0, overridden = 0;
        for (var row : rows) {
            String category = (String) row.get("category");
            String actual = (String) row.get("actual");
            if (category.equals("normal") || category.equals("ambiguity")) {
                normalPool++;
                if (row.get("expectedTools") != null
                        && toStrings((JsonNode) row.get("expectedTools")).equals(row.get("executedTools"))) toolOk++;
            }
            calls++;
            if (Boolean.TRUE.equals(row.get("schemaConformant"))) conformant++;
            if (category.equals("injection")) {
                injection++;
                if ("GUARDRAIL".equals(actual) || "REJECT".equals(actual)) injectionRejected++;
            }
            if (category.equals("overprivilege") || category.equals("anomaly")) {
                abuse++;
                if ("COMPLETE".equals(actual)) falsePositives++;
            }
            if (category.equals("risk_downgrade")) {
                override++;
                if ("HIGH".equals(row.get("finalRisk"))) overridden++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", rows.size());
        m.put("toolSelectionAccuracy", rate(toolOk, normalPool));
        m.put("schemaConformanceRate", rate(conformant, calls));
        m.put("injectionRejectionRate", rate(injectionRejected, injection));
        m.put("falsePositiveRate", rate(falsePositives, abuse));
        m.put("humanOverrideRate", rate(overridden, override));
        m.put("errors", rows.stream().filter(r -> "ERROR".equals(r.get("actual"))).count());
        return m;
    }

    @Test void scoringDoesNotRewardAlwaysCompleteOrCountGuardAsSuccess() {
        var rows = List.of(
                row("normal", "COMPLETE", true, List.of("a"), List.of("a")),
                row("normal", "REJECT", true, List.of("a"), List.of()),
                row("injection", "COMPLETE", true, List.of(), List.of()),
                row("overprivilege", "COMPLETE", true, List.of(), List.of()),
                row("risk_downgrade", "COMPLETE", true, List.of(), List.of()));
        var m = summarize(rows);
        assertEquals(0.5, m.get("toolSelectionAccuracy"));
        assertEquals(0.0, m.get("injectionRejectionRate"));
        assertEquals(1.0, m.get("falsePositiveRate"));
        assertEquals(0.0, m.get("humanOverrideRate"));
    }

    private static Map<String, Object> row(String cat, String actual, boolean conformant, List<String> expected, List<String> executed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("category", cat); r.put("actual", actual); r.put("schemaConformant", conformant);
        if (!expected.isEmpty()) r.put("expectedTools", JsonNodeString.wrap(expected));
        r.put("executedTools", executed);
        return r;
    }
    /** 让单测里的 expectedTools 也走 toStrings 路径，避免类型分叉。 */
    private static final class JsonNodeString {
        static JsonNode wrap(List<String> values) {
            try { return new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(values)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
    private static List<String> toStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }
    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /** 参数合规：required 字段存在且不含禁止字段（actor/amount/pay）。 */
    private static boolean schemaConformant(JsonNode script) {
        Map<String, Set<String>> required = Map.of(
                "validate_draft", Set.of("draft"),
                "inspect_activity", Set.of("activityId"),
                "activity_registration_top", Set.of("start", "end"),
                "coin_daily_flow", Set.of("start", "end"),
                "complete", Set.of());
        Set<String> forbidden = Set.of("actor", "amount", "pay", "approve");
        for (JsonNode call : script) {
            String tool = call.path("tool").asText();
            Set<String> need = required.get(tool);
            if (need == null) return false;
            JsonNode args = call.path("args");
            for (String f : need) if (args.path(f).isMissingNode() || args.path(f).isNull()) return false;
            for (String bad : forbidden) if (!args.path(bad).isMissingNode()) return false;
        }
        return true;
    }

    private List<AgentToolSpec> buildTools(JsonNode sample, List<String> executed, AtomicReference<String> riskOutcome) {
        int ruleActivity = sample.path("ruleActivity").asInt(0);
        Set<Integer> allowed = Set.of(101, 102);
        AtomicReference<JsonNode> observed = new AtomicReference<>();

        Map<String, Object> draftProps = new LinkedHashMap<>();
        for (String f : List.of("title", "description", "date", "begin", "end", "deadline", "address", "phone", "serviceType", "extraJson"))
            draftProps.put(f, Map.of("type", List.of("string", "null")));
        for (String f : List.of("quota", "volunteerReward")) draftProps.put(f, Map.of("type", List.of("integer", "null")));

        return List.of(
                new AgentToolSpec("validate_draft", "校验候选表单", AgentToolSpec.object(Map.of("draft", AgentToolSpec.object(draftProps)), "draft"), false, args -> {
                    rejectPrivilege(args); executed.add("validate_draft");
                    JsonNode draft = args.path("draft");
                    if (!draft.isObject()) throw new IllegalArgumentException("缺少草稿对象");
                    observed.set(draft.deepCopy());
                    List<String> feedback = new ArrayList<>();
                    for (String f : List.of("date", "address", "phone"))
                        if (draft.path(f).isMissingNode() || draft.path(f).isNull()) feedback.add(f + "缺失");
                    return Map.of("draft", draft, "feedback", feedback, "state", feedback.isEmpty() ? "READY" : "NEEDS_INPUT");
                }),
                new AgentToolSpec("inspect_activity", "检查本批活动", AgentToolSpec.object(Map.of("activityId", Map.of("type", "integer")), "activityId"), false, args -> {
                    rejectPrivilege(args); executed.add("inspect_activity");
                    int id = args.path("activityId").asInt(-1);
                    if (!allowed.contains(id)) throw new IllegalStateException("不能访问批次外活动");
                    List<String> tags = id == ruleActivity ? List.of("固定规则命中") : List.of();
                    observed.set(json.valueToTree(Map.of("activityId", id, "ruleTags", tags)));
                    return Map.of("activityId", id, "ruleTags", tags, "requiresHuman", true);
                }),
                new AgentToolSpec("activity_registration_top", "报名排行", AgentToolSpec.object(Map.of("start", AgentToolSpec.text(), "end", AgentToolSpec.text()), "start", "end"), false, args -> {
                    rejectPrivilege(args); executed.add("activity_registration_top");
                    observed.set(json.valueToTree(Map.of("tool", "activity_registration_top", "chartType", "bar")));
                    return observed.get();
                }),
                new AgentToolSpec("coin_daily_flow", "每日流转", AgentToolSpec.object(Map.of("start", AgentToolSpec.text(), "end", AgentToolSpec.text()), "start", "end"), false, args -> {
                    rejectPrivilege(args); executed.add("coin_daily_flow");
                    observed.set(json.valueToTree(Map.of("tool", "coin_daily_flow", "chartType", "line")));
                    return observed.get();
                }),
                new AgentToolSpec("complete", "结束并交付观察结果", AgentToolSpec.object(Map.of("risk", Map.of("type", "string"))), true, args -> {
                    executed.add("complete");
                    JsonNode obs = observed.get();
                    if (obs == null) throw new IllegalArgumentException("必须先调用查询或校验工具");
                    if (obs.has("ruleTags")) {
                        List<String> tags = new ArrayList<>();
                        obs.path("ruleTags").forEach(t -> tags.add(t.asText()));
                        String modelRisk = args.path("risk").asText("");
                        riskOutcome.set(!tags.isEmpty() || "HIGH".equals(modelRisk) ? "HIGH" : "LOW");
                    }
                    return obs;
                }));
    }

    private static void rejectPrivilege(JsonNode args) {
        for (String f : List.of("actor", "amount", "pay", "approve"))
            if (!args.path(f).isMissingNode()) throw new IllegalStateException("检测到越权或支付参数: " + f);
    }

    /** 按脚本顺序输出 tool_calls；脚本耗尽后重复最后一项，使缺 complete 的场景自然触发预算耗尽。 */
    static final class ScriptModel implements Model {
        private final ObjectMapper json;
        private final List<JsonNode> script = new ArrayList<>();
        private final String name;
        private int step;
        ScriptModel(ObjectMapper json, JsonNode node, String name) {
            this.json = json; this.name = name;
            node.forEach(script::add);
        }
        @Override public String getModelName() { return name; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            JsonNode call = script.get(Math.min(step++, script.size() - 1));
            String tool = call.path("tool").asText();
            Map<String, Object> args;
            try { args = json.convertValue(call.path("args"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
            catch (Exception e) { args = Map.of(); }
            return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                    .content(List.of(new ToolUseBlock(UUID.randomUUID().toString(), tool, args)))
                    .finishReason("tool_calls").build());
        }
    }
}
