package org.example.timecoinweb.agent;

import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.AiChatService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.crypto.Hash;
import java.time.LocalDateTime;
import java.util.*;

/** 固定工具图：生成→校验→收集补充→待用户确认。无支付或发布工具。 */
@Service
@RequiredArgsConstructor
public class PublishAgent {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiChatService model;
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
        JsonNode fields=model.planAgentTool("活动表单对象，允许字段title,quota,description,volunteerReward,date(yyyy-MM-dd),begin/end(HH:mm:ss),deadline(yyyy-MM-dd'T'HH:mm:ss),address,phone,serviceType,extraJson(JSON字符串)。不要输出其他字段。",text);
        Activity activity=validator.decode(fields);
        List<String> issues=validator.issues(activity,LocalDateTime.now());
        return new TransactionTemplate(manager).execute(tx->{
            // 并发重复生成只允许落库一次；模型调用不占数据库事务。
            jdbc.queryForObject("select id from `user` where id=? for update",Integer.class,actor);
            var duplicate=jdbc.queryForList("select id,input_hash from agent_task where owner_id=? and request_key=?",actor,key);
            if(!duplicate.isEmpty()){sameInput(duplicate.get(0),hash);return get(actor,(String)duplicate.get(0).get("id"));}
            String id=UUID.randomUUID().toString();
            jdbc.update("insert into agent_task(id,owner_id,request_key,input_hash,state,draft,feedback) values(?,?,?,?,?,?,?)",id,actor,key,hash,issues.isEmpty()?"READY":"NEEDS_INPUT",encode(fields),encode(issues));
            audit(id,actor,"GENERATE_DRAFT","模型生成候选表单，未执行发布");
            audit(id,actor,"VALIDATE_DRAFT",encode(issues));
            return get(actor,id);
        });
    }
    public Map<String,Object> get(int actor,String id) {
        requireElder(actor);
        var rows=jdbc.queryForList("select * from agent_task where id=? and owner_id=?",id,actor);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        var row=rows.get(0);
        Map<String,Object> view=new LinkedHashMap<>();
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
