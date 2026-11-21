package org.example.timecoinweb.agent;

import tools.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.crypto.Hash;
import java.time.LocalDateTime;
import java.util.*;

/** ReAct生成、校验与修订候选表单；持久化和人工发布确认在模型循环之外。 */
@Service
@RequiredArgsConstructor
public class PublishAgent {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentRuntime runtime;
    private final ActivityDraftValidator validator;
    private final PlatformTransactionManager manager;
    private final BlockchainProperties chain;

    public Map<String,Object> create(int actor,String key,String text) throws Exception {
        requireElder(actor);
        if(key==null || !key.matches("[a-zA-Z0-9:_-]{8,100}")) throw new IllegalArgumentException("缺少有效幂等键");
        if(text==null || text.isBlank() || text.length()>4000) throw new IllegalArgumentException("需求须为1至4000字符");
        String hash=Hash.sha3String(text);
        var previous=jdbc.queryForList("select id,input_hash from agent_task where owner_id=? and request_key=?",actor,key);
        if(!previous.isEmpty()) {sameInput(previous.get(0),hash);return get(actor,(String)previous.get(0).get("id"));}
        String runId=UUID.randomUUID().toString();
        var checked=new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        Map<String,Object> properties=new LinkedHashMap<>();
        for(String field:List.of("title","description","date","begin","end","deadline","address","phone","serviceType","extraJson"))
            properties.put(field,Map.of("type",List.of("string","null")));
        for(String field:List.of("quota","volunteerReward")) properties.put(field,Map.of("type",List.of("integer","null")));
        JsonNode fields=runtime.run(actor,runId,"生成活动表单，日期为yyyy-MM-dd，时间为HH:mm:ss，deadline为yyyy-MM-dd'T'HH:mm:ss。"
                +"先validate_draft观察问题，有已知事实可修正时重新校验；缺失信息不得猜测，保留问题交给用户补充，最后complete。extraJson必须为JSON字符串。",text,List.of(
                new AgentToolSpec("validate_draft","校验候选活动表单并返回需要用户补充的字段",AgentToolSpec.object(Map.of("draft",AgentToolSpec.object(properties)),"draft"),false,args->{
                    requireElder(actor); JsonNode draft=args.path("draft");
                    var decoded=validator.decode(draft); var feedback=validator.issues(decoded,LocalDateTime.now());
                    checked.set(draft.deepCopy());return Map.of("draft",draft,"feedback",feedback,"state",feedback.isEmpty()?"READY":"NEEDS_INPUT");
                }),
                new AgentToolSpec("complete","结束草稿编排，返回最近一次通过类型校验的草稿；不会发布或扣费",AgentToolSpec.object(Map.of()),true,args->{
                    requireElder(actor); if(checked.get()==null)throw new IllegalArgumentException("必须先调用validate_draft");return checked.get();
                })));
        Activity activity=validator.decode(fields);
        List<String> issues=validator.issues(activity,LocalDateTime.now());
        return new TransactionTemplate(manager).execute(tx->{
            // 并发重复生成只允许落库一次；模型调用不占数据库事务。
            jdbc.queryForObject("select id from `user` where id=? for update",Integer.class,actor);
            requireElder(actor);
            var duplicate=jdbc.queryForList("select id,input_hash from agent_task where owner_id=? and request_key=?",actor,key);
            if(!duplicate.isEmpty()){sameInput(duplicate.get(0),hash);return get(actor,(String)duplicate.get(0).get("id"));}
            String id=runId;
            jdbc.update("insert into agent_task(id,owner_id,request_key,input_hash,state,draft,feedback) values(?,?,?,?,?,?,?)",id,actor,key,hash,issues.isEmpty()?"READY":"NEEDS_INPUT",encode(fields),encode(issues));
            audit(id,actor,"GENERATE_DRAFT","模型生成候选表单，未执行发布");
            audit(id,actor,"VALIDATE_DRAFT",encode(issues));
            return get(actor,id);
        });
    }
    /** 个人历史任务列表：只返回本人任务，白名单字段，按创建时间倒序分页。 */
    public Map<String,Object> list(int actor,int page,int size) {
        requireElder(actor);
        if(page<1 || size<1 || size>50) throw new IllegalArgumentException("分页参数不合法");
        int offset=Math.multiplyExact(page-1,size);
        List<Map<String,Object>> rows=jdbc.queryForList("select id,state,revision,created_at from agent_task where owner_id=? order by created_at desc,id desc limit ? offset ?",actor,size,offset);
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:rows){Map<String,Object> item=new LinkedHashMap<>();item.put("id",row.get("id"));item.put("state",row.get("state"));
            item.put("revision",row.get("revision"));item.put("createdAt",row.get("created_at"));items.add(item);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("page",page);result.put("size",size);
        result.put("total",jdbc.queryForObject("select count(*) from agent_task where owner_id=?",Long.class,actor));
        return result;
    }
    public Map<String,Object> get(int actor,String id) {
        requireElder(actor);
        var rows=jdbc.queryForList("select * from agent_task where id=? and owner_id=?",id,actor);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        var row=rows.get(0);
        Map<String,Object> view=new LinkedHashMap<>();
        view.put("traceId",id);
        for(String field:List.of("id","state","revision")) view.put(field,row.get(field));
        view.put("draft",read((String)row.get("draft")));view.put("feedback",read((String)row.get("feedback")));
        view.put("publishFee",fee());
        view.put("result",row.get("result_json")==null?null:read((String)row.get("result_json")));
        view.put("steps",jdbc.queryForList("select tool,detail,created_at from agent_audit where task_id=? order by created_at,id",id));
        return view;
    }
    public Map<String,Object> revise(int actor,String id,int revision,JsonNode fields) {
        requireElder(actor); Activity a=validator.decode(fields);
        var issues=validator.issues(a,LocalDateTime.now());
        return new TransactionTemplate(manager).execute(tx->{
            int changed=jdbc.update("update agent_task set draft=?,feedback=?,state=?,revision=revision+1 where id=? and owner_id=? and revision=? and state in ('READY','NEEDS_INPUT')",encode(fields),encode(issues),issues.isEmpty()?"READY":"NEEDS_INPUT",id,actor,revision);
            if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已变化，请重新加载");
            audit(id,actor,"FILL_FORM","用户补充表单并重新校验");audit(id,actor,"VALIDATE_DRAFT",encode(issues));
            return get(actor,id);
        });
    }
    public void requireElder(int actor) {
        Integer role=jdbc.queryForObject("select role from `user` where id=?",Integer.class,actor);
        if(role==null || role!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要老人身份");
    }
    public String fee(){return chain.isEnabled()?chain.getOldPublishActivityCost().toString():"0";}
    public void audit(String id,int actor,String tool,String detail){jdbc.update("insert into agent_audit(id,task_id,actor_id,tool,detail) values(?,?,?,?,?)",UUID.randomUUID().toString(),id,actor,tool,detail);}
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法保存Agent数据",e);}}
    public JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("任务数据损坏",e);}}
    private void sameInput(Map<String,Object> row,String hash){if(!hash.equals(row.get("input_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"幂等键不能用于不同需求");}
}
