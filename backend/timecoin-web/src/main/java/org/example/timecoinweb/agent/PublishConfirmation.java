package org.example.timecoinweb.agent;

import lombok.RequiredArgsConstructor;
import org.example.timecoinweb.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.Map;

/** 仅用户确认接口调用；不是暴露给模型的工具。 */
@Service
@RequiredArgsConstructor
public class PublishConfirmation {
    private final PublishAgent agent;
    private final ActivityDraftValidator validator;
    private final JdbcTemplate jdbc;
    private final UserService users;
    @Transactional
    public Object confirm(int actor,String id,int revision,String acceptedFee,boolean confirmed){
        agent.requireElder(actor);
        if(!confirmed)throw new IllegalArgumentException("必须由用户明确确认发布与费用");
        var rows=jdbc.queryForList("select * from agent_task where id=? and owner_id=? for update",id,actor);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        Map<String,Object> row=rows.get(0);
        if(((Number)row.get("revision")).intValue()!=revision)throw new ResponseStatusException(HttpStatus.CONFLICT,"表单已变化，请重新核对");
        if("SUBMITTED".equals(row.get("state")))return agent.read((String)row.get("result_json"));
        if(!"READY".equals(row.get("state")) || !agent.fee().equals(acceptedFee))throw new ResponseStatusException(HttpStatus.CONFLICT,"表单未就绪或费用已变化");
        var draft=validator.decode(agent.read((String)row.get("draft")));
        var issues=validator.issues(draft,LocalDateTime.now());
        if(!issues.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"表单已过期或不完整："+String.join("；",issues));
        Object result=users.oldAddActivity(draft,actor,"agent:"+id);
        jdbc.update("update agent_task set state='SUBMITTED',result_json=? where id=?",agent.encode(result),id);
        agent.audit(id,actor,"USER_CONFIRM_PUBLISH","用户确认费用="+acceptedFee+"；结果以结算状态为准");
        return result;
    }
}
