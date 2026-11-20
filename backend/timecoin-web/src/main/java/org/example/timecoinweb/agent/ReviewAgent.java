package org.example.timecoinweb.agent;

import tools.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Activity;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.web3j.crypto.Hash;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReviewAgent {
    private final JdbcTemplate jdbc;
    private final ActivityMapper activities;
    private final SettlementService settlements;
    private final ActivityDraftValidator validator;
    private final AgentRuntime runtime;
    private final ObjectMapper json;
    private final PublishAgent audit;
    private final PlatformTransactionManager manager;
    @Value("${agent.review.sensitive-terms:私下转账,银行卡密码,验证码,现金返利}") private String sensitiveTerms;

    public void requireAdmin(int actor){if(!Integer.valueOf(3).equals(jdbc.queryForObject("select role from `user` where id=?",Integer.class,actor)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员身份");}
    public List<Map<String,Object>> candidates(int actor){requireAdmin(actor);return jdbc.queryForList("select id,title,address,date from activity where status=1 order by update_time desc,id desc limit 50");}
    public List<Map<String,Object>> list(int actor){requireAdmin(actor);return traced(jdbc.queryForList("select * from agent_review order by created_at desc,id desc limit 100"));}
    public List<Map<String,Object>> screen(int actor,String key,List<Integer> requested) throws Exception {
        requireAdmin(actor);
        if(key==null || !key.matches("[a-zA-Z0-9:_-]{8,100}"))throw new IllegalArgumentException("缺少有效幂等键");
        if(requested==null || requested.isEmpty() || requested.size()>5 || requested.stream().anyMatch(x->x==null || x<1))throw new IllegalArgumentException("每批选择1至5个活动");
        List<Integer> ids=new ArrayList<>(new TreeSet<>(requested));String hash=Hash.sha3String(ids.toString());
        var prior=existing(actor,key,hash);if(prior!=null)return prior;
        Map<Integer,Activity> snapshots=new LinkedHashMap<>();
        for(int id:ids){Activity a=activities.selectByActId(id);pending(a);snapshots.put(id,a);}
        // 每次请求独立工具集，模型只能检查本批已授权活动；不发送联系方式、用户身份或token。
        List<Map<String,Object>> input=new ArrayList<>();
        for(Activity a:snapshots.values()){
            Map<String,Object> fields=new LinkedHashMap<>();fields.put("activityId",a.getId());fields.put("title",a.getTitle());fields.put("description",a.getDescription());fields.put("address",a.getAddress());fields.put("date",a.getDate());input.add(fields);
        }
        String runId=UUID.randomUUID().toString(); Set<Integer> inspected=new HashSet<>();
        var itemSchema=AgentToolSpec.object(Map.of("activityId",Map.of("type","integer"),"risk",Map.of("type","string","enum",List.of("LOW","HIGH")),
                "tags",Map.of("type","array","items",Map.of("type","string","maxLength",100),"maxItems",10),
                "reason",Map.of("type","string","minLength",1,"maxLength",1000)),"activityId","risk","tags","reason");
        JsonNode plan=runtime.run(actor,runId,"逐项调用inspect_activity观察固定规则，再结合文本提出风险依据；最后complete提交整批建议。每项必须人工审核，不能改变活动状态。",
                json.writeValueAsString(input),List.of(
                new AgentToolSpec("inspect_activity","检查本批活动的字段、敏感内容及地址规则",AgentToolSpec.object(Map.of("activityId",Map.of("type","integer","enum",ids)),"activityId"),false,args->{
                    requireAdmin(actor);int id=args.path("activityId").intValue();
                    if(!snapshots.containsKey(id))throw new IllegalArgumentException("不能访问批次外活动");
                    var tags=ruleTags(snapshots.get(id));inspected.add(id);return Map.of("activityId",id,"ruleTags",tags,"requiresHuman",true);
                }),
                new AgentToolSpec("complete","提交整批风险建议，不执行审批",AgentToolSpec.object(Map.of("reviews",Map.of("type","array","items",itemSchema,"minItems",ids.size(),"maxItems",ids.size())),"reviews"),true,args->{
                    requireAdmin(actor);if(!inspected.containsAll(ids))throw new IllegalArgumentException("必须先检查每个活动");predictions(args,ids);return args;
                })));
        Map<Integer,JsonNode> byId=predictions(plan,ids);
        return new TransactionTemplate(manager).execute(tx->{
            jdbc.queryForObject("select id from `user` where id=? for update",Integer.class,actor);
            requireAdmin(actor);
            var duplicate=existing(actor,key,hash);if(duplicate!=null)return duplicate;
            String batch=runId;
            jdbc.update("insert into agent_review_batch(id,actor_id,request_key,input_hash) values(?,?,?,?)",batch,actor,key,hash);
            for(int id:ids){
                Activity a=activities.lockById(id);pending(a);
                String snapshot=audit.encode(a), expected=audit.encode(snapshots.get(id));
                if(!snapshot.equals(expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"活动已变更，请重新预筛");
                List<String> tags=ruleTags(a);boolean ruleRisk=!tags.isEmpty();
                JsonNode prediction=byId.get(id);prediction.path("tags").forEach(t->tags.add(t.asText()));
                String risk=ruleRisk || "HIGH".equals(prediction.path("risk").asText())?"HIGH":"LOW";
                String review=UUID.randomUUID().toString();
                jdbc.update("insert into agent_review(id,batch_id,activity_id,snapshot_hash,snapshot,risk,findings,state) values(?,?,?,?,?,?,?,?)",review,batch,id,Hash.sha3String(snapshot),snapshot,risk,audit.encode(Map.of("tags",tags,"reason",prediction.path("reason").asText())),"AWAITING_HUMAN");
                audit.audit(review,actor,"SCREEN_ACTIVITY","风险="+risk+"；仅预筛，不改变活动状态");
            }
            return traced(jdbc.queryForList("select * from agent_review where batch_id=?",batch));
        });
    }
    public Object decide(int actor,String id,String decision,String note,boolean confirmed){
        requireAdmin(actor);
        if(!confirmed || !Set.of("APPROVE","REJECT").contains(decision==null?"":decision) || note==null || note.isBlank() || note.length()>500)throw new IllegalArgumentException("人工确认、决定和1至500字审核说明必填");
        return new TransactionTemplate(manager).execute(tx->{
            var rows=jdbc.queryForList("select * from agent_review where id=? for update",id);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"预筛记录不存在");
            var r=rows.get(0);
            if("DECIDED".equals(r.get("state"))){
                if(!decision.equals(r.get("decision")) || !note.equals(r.get("note")) || ((Number)r.get("reviewer_id")).intValue()!=actor)throw new ResponseStatusException(HttpStatus.CONFLICT,"该记录已有人工决定");
                return r;
            }
            Activity a=activities.lockById(((Number)r.get("activity_id")).intValue());pending(a);
            if(!Hash.sha3String(audit.encode(a)).equals(r.get("snapshot_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"活动已变化，旧预筛不可审批，请重新预筛");
            settlements.requireActivityMutable(a);
            // 审核不是编辑表单：允许拒绝信息不完整的活动，批准则必须满足完整性要求。
            if("APPROVE".equals(decision)) {
                var issues=validator.issues(a,java.time.LocalDateTime.now());
                if(!issues.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"活动资料不完整，不能批准："+String.join("；",issues));
            }
            Integer admin=jdbc.queryForObject("select id from administrator where user_id=?",Integer.class,actor);
            jdbc.update("update activity set status=?,message=?,administrator_id=?,update_time=CURRENT_TIMESTAMP where id=? and status=1","APPROVE".equals(decision)?2:4,note,admin,a.getId());
            jdbc.update("update agent_review set state='DECIDED',reviewer_id=?,decision=?,note=?,decided_at=CURRENT_TIMESTAMP where id=?",actor,decision,note,id);
            audit.audit(id,actor,"HUMAN_REVIEW",decision+"："+note);
            return jdbc.queryForMap("select * from agent_review where id=?",id);
        });
    }
    private List<String> ruleTags(Activity a){
        List<String> tags=new ArrayList<>();
        if(a.getAddress()==null || a.getAddress().trim().length()<6 || a.getAddress().matches(".*(附近|待定|到时联系|另行通知).*"))tags.add("地址不明确");
        var fieldIssues=validator.issues(json.convertValue(a,Activity.class),java.time.LocalDateTime.now());
        if(!fieldIssues.isEmpty()){tags.add("信息缺失或字段校验未通过");tags.addAll(fieldIssues);}
        String text=String.valueOf(a.getTitle())+" "+String.valueOf(a.getDescription());
        for(String word:sensitiveTerms.split(","))if(!word.isBlank() && text.contains(word.trim())){tags.add("命中敏感内容规则");break;}
        return tags;
    }
    private Map<Integer,JsonNode> predictions(JsonNode plan,List<Integer> ids){
        JsonNode results=plan.get("reviews");
        if(plan.size()!=1 || results==null || !results.isArray() || results.size()!=ids.size())throw new IllegalArgumentException("预筛结果结构或数量错误");
        Map<Integer,JsonNode> byId=new HashMap<>();
        for(JsonNode item:results){
            if(!item.isObject() || item.size()!=4 || !item.path("activityId").isIntegralNumber() || !item.path("activityId").canConvertToInt() || !Set.of("LOW","HIGH").contains(item.path("risk").asText()) || !item.path("tags").isArray() || item.path("tags").size()>10 || !item.path("reason").isTextual() || item.path("reason").asText().isBlank() || item.path("reason").asText().length()>1000)throw new IllegalArgumentException("预筛结果字段不合法");
            int id=item.path("activityId").intValue();if(!ids.contains(id) || byId.put(id,item)!=null)throw new IllegalArgumentException("预筛结果活动编号不匹配");
            for(JsonNode tag:item.path("tags"))if(!tag.isTextual() || tag.asText().length()>100)throw new IllegalArgumentException("风险标签不合法");
        }
        return byId;
    }
    private void pending(Activity a){if(a==null || a.getStatus()==null || a.getStatus()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"仅待审核活动可预筛或审批");}
    private List<Map<String,Object>> existing(int actor,String key,String hash){
        var rows=jdbc.queryForList("select id,input_hash from agent_review_batch where actor_id=? and request_key=?",actor,key);
        if(rows.isEmpty())return null;
        if(!hash.equals(rows.get(0).get("input_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"幂等键参数冲突");
        return traced(jdbc.queryForList("select * from agent_review where batch_id=?",rows.get(0).get("id")));
    }
    private List<Map<String,Object>> traced(List<Map<String,Object>> rows){
        rows.forEach(row->row.put("traceId",row.get("batch_id")));return rows;
    }
}
