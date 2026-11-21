package org.example.timecoinweb.agent;

import lombok.RequiredArgsConstructor;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.example.timecoinweb.service.SettlementService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;

/** 结算答谢Agent：ReAct只读预检（活动归属/结束/签到/余额/未决结算），模型只组装提案，写操作在人工确认层。 */
@Service
@RequiredArgsConstructor
public class SettlementAgent {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentRuntime runtime;
    private final ActivityMapper activities;
    private final OldMapper olds;
    private final UserMapper users;
    private final VolMapper vols;
    private final SettlementService settlements;
    private final BlockchainProperties chain;
    private final PublishAgent audit;
    private final PlatformTransactionManager manager;

    public Map<String, Object> prepare(int actor, String key, int activityId) throws Exception {
        requireElder(actor);
        if (key == null || !key.matches("[a-zA-Z0-9:_-]{8,100}")) throw new IllegalArgumentException("缺少有效幂等键");
        if (activityId < 1) throw new IllegalArgumentException("活动编号不合法");
        String hash = Hash.sha3String("settle:" + activityId);
        var prior = jdbc.queryForList("select id from agent_settlement where owner_id=? and request_key=?", actor, key);
        if (!prior.isEmpty()) return get(actor, (String) prior.get(0).get("id"));

        // 服务端预取事实快照，模型无法编造；工具闭包只返回这些只读结论。
        Activity activity = activities.selectByActId(activityId);
        Integer oldId = olds.selectOldId(actor);
        if (activity == null || oldId == null || !Objects.equals(activity.getOldId(), oldId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该活动");
        LocalDateTime end = activity.getDate() == null || activity.getEnd() == null ? null
                : LocalDateTime.of(activity.getDate(), activity.getEnd());
        boolean ended = end != null && !LocalDateTime.now().isBefore(end);
        int reward = activity.getVolunteerReward() == null ? 0 : activity.getVolunteerReward();
        Long balance = jdbc.queryForObject("select coin_balance from `user` where id=?", Long.class, actor);
        var candidates = jdbc.queryForList("select v.user_id as userId, av.sign, av.status, av.reward_paid as rewardPaid "
                + "from activity_volunteer av join volunteer v on v.id=av.volunteer_id where av.activity_id=? order by v.user_id", activityId);
        long signedPending = candidates.stream().filter(r -> ((Number) r.get("sign")).intValue() == 1
                && ((Number) r.get("status")).intValue() != 1).count();
        long totalCost = signedPending * reward;
        int maxVolunteers = chain.getBatchRewardMaxVolunteers();
        BigInteger cap = chain.getBatchRewardTotalCap();
        boolean withinBatch = maxVolunteers <= 0 || signedPending <= maxVolunteers;
        boolean withinCap = cap == null || cap.signum() <= 0 || BigInteger.valueOf(totalCost).compareTo(cap) <= 0;
        boolean enough = balance != null && balance >= totalCost;

        String runId = UUID.randomUUID().toString();
        var observed = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        runtime.run(actor, runId, "结算答谢只读预检：逐项调用inspect工具观察服务端事实，最后调用complete提交提案。"
                + "只能使用工具返回的数字，不得编造金额、人数或余额；不执行任何发放或结算。",
                "为活动" + activityId + "生成答谢预检提案", List.of(
                new AgentToolSpec("inspect_activity", "检查活动归属、是否结束及单人答谢额",
                        AgentToolSpec.object(Map.of("activityId", Map.of("type", "integer")), "activityId"), false, args -> {
                    requireElder(actor);
                    int id = args.path("activityId").intValue();
                    if (id != activityId) throw new IllegalArgumentException("只能检查本活动");
                    return Map.of("activityId", id, "owned", true, "ended", ended, "rewardPerVolunteer", reward);
                }),
                new AgentToolSpec("inspect_volunteers", "统计已签到待答谢人数",
                        AgentToolSpec.object(Map.of()), false, args -> {
                    requireElder(actor);
                    return Map.of("signedPending", signedPending, "candidates", candidates.size());
                }),
                new AgentToolSpec("inspect_balance", "读取老人可用余额",
                        AgentToolSpec.object(Map.of()), false, args -> {
                    requireElder(actor);
                    return Map.of("balance", balance == null ? 0 : balance, "required", totalCost);
                }),
                new AgentToolSpec("inspect_pending", "检查该活动是否已有在途结算",
                        AgentToolSpec.object(Map.of()), false, args -> {
                    requireElder(actor);
                    List<String> pending = jdbc.queryForList("select id from settlement_order where activity_id=? and state<>'SUCCEEDED'", String.class, activityId);
                    return Map.of("pendingSettlements", pending.size());
                }),
                new AgentToolSpec("complete", "提交由服务端事实组装的答谢提案，不执行发放",
                        AgentToolSpec.object(Map.of("note", AgentToolSpec.text())), true, args -> {
                    requireElder(actor);
                    Map<String, Object> proposal = new LinkedHashMap<>();
                    proposal.put("activityId", activityId);
                    proposal.put("rewardPerVolunteer", reward);
                    proposal.put("signedPending", signedPending);
                    proposal.put("totalCost", totalCost);
                    proposal.put("balance", balance == null ? 0 : balance);
                    proposal.put("ended", ended);
                    proposal.put("withinBatch", withinBatch);
                    proposal.put("withinCap", withinCap);
                    proposal.put("enoughBalance", enough);
                    proposal.put("note", args.path("note").asText(""));
                    observed.set(json.valueToTree(proposal));
                    return proposal;
                })));

        // 服务端对提案做权威复核：模型只能复述这些值，任何不一致以服务端为准。
        JsonNode proposal = observed.get();
        if (proposal == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "预检未完成");
        Map<String, Object> authoritative = new LinkedHashMap<>();
        authoritative.put("activityId", activityId);
        authoritative.put("rewardPerVolunteer", reward);
        authoritative.put("signedPending", signedPending);
        authoritative.put("totalCost", totalCost);
        authoritative.put("balance", balance == null ? 0 : balance);
        authoritative.put("ended", ended);
        authoritative.put("withinBatch", withinBatch);
        authoritative.put("withinCap", withinCap);
        authoritative.put("enoughBalance", enough);
        authoritative.put("eligible", ended && signedPending > 0 && withinBatch && withinCap && enough);
        authoritative.put("note", proposal.path("note").asText(""));

        return new TransactionTemplate(manager).execute(tx -> {
            jdbc.queryForObject("select id from `user` where id=? for update", Integer.class, actor);
            var duplicate = jdbc.queryForList("select id from agent_settlement where owner_id=? and request_key=?", actor, key);
            if (!duplicate.isEmpty()) return get(actor, (String) duplicate.get(0).get("id"));
            String id = runId;
            jdbc.update("insert into agent_settlement(id,owner_id,request_key,input_hash,activity_id,state,proposal) values(?,?,?,?,?,?,?)",
                    id, actor, key, hash, activityId, "PREPARED", encode(authoritative));
            audit.audit(id, actor, "SETTLEMENT_PRECHECK", "只读预检，未发放时间币");
            return get(actor, id);
        });
    }

    public Map<String, Object> get(int actor, String id) {
        requireElder(actor);
        var rows = jdbc.queryForList("select * from agent_settlement where id=? and owner_id=?", id, actor);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提案不存在");
        var row = rows.get(0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", id);
        for (String f : List.of("id", "activityId", "state", "revision")) view.put(f, row.get(f));
        view.put("proposal", read((String) row.get("proposal")));
        view.put("result", row.get("result_json") == null ? null : read((String) row.get("result_json")));
        view.put("steps", jdbc.queryForList("select tool,detail,created_at from agent_audit where task_id=? order by created_at,id", id));
        return view;
    }

    public void requireElder(int actor) {
        Integer role = jdbc.queryForObject("select role from `user` where id=?", Integer.class, actor);
        if (role == null || role != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要老人身份");
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("无法保存提案", e); } }
    public JsonNode read(String value) { try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("提案数据损坏", e); } }
}
