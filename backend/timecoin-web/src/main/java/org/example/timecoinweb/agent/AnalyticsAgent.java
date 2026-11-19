package org.example.timecoinweb.agent;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.crypto.Hash;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 模型只选择枚举工具和日期；所有SQL固定且仅做聚合读取。 */
@Service
@RequiredArgsConstructor
public class AnalyticsAgent {
    private final AgentRuntime runtime;
    private final ReviewAgent authorization;
    private final JdbcTemplate jdbc;
    private final PublishAgent audit;
    private final tools.jackson.databind.ObjectMapper json;
    // 现有业务写入LocalDateTime，无时区列；必须按同一JVM业务时区解释，不能伪装已转换。
    private static final ZoneId ZONE=ZoneId.systemDefault();
    private static final Set<String> TOOLS=Set.of("activity_registration_top","coin_daily_flow");

    public Map<String,Object> answer(int actor,String question) throws Exception {
        authorization.requireAdmin(actor);
        if(question==null || question.isBlank() || question.length()>2000)throw new IllegalArgumentException("问题须为1至2000字");
        String id=UUID.randomUUID().toString();
        var observed=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        List<AgentToolSpec> tools=new ArrayList<>();
        for(String name:TOOLS) tools.add(new AgentToolSpec(name,"读取"+(name.equals("coin_daily_flow")?"时间币每日流转金额":"保留报名关系排行")+"；start包含，end不包含，最多31天",
                AgentToolSpec.object(Map.of("start",AgentToolSpec.text(),"end",AgentToolSpec.text()),"start","end"),false,args->{
                    var plan=args.deepCopy();((tools.jackson.databind.node.ObjectNode)plan).put("tool",name);
                    var result=query(actor,plan,id);observed.set(result);return result;
                }));
        tools.add(new AgentToolSpec("complete","返回最近一次查询的真实结果与统计口径，数值结论由服务端生成",AgentToolSpec.object(Map.of()),true,args->{
            authorization.requireAdmin(actor);if(observed.get()==null)throw new IllegalArgumentException("必须先调用统计查询工具");return observed.get();
        }));
        JsonNode result=runtime.run(actor,id,"仅支持活动报名排行和时间币转账台账每日趋势，不支持任意SQL。今天为"+LocalDate.now(ZONE)+"，业务时区"+ZONE
                +"。上周指本周一之前的完整周一至周日。观察查询结果与口径后，调用complete返回结果。",question,tools);
        Map<String,Object> response=json.convertValue(result,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
        authorization.requireAdmin(actor);
        audit.audit(id,actor,"ANALYTICS_READ",audit.encode(Map.of("questionHash",Hash.sha3String(question),"result",response)));
        return response;
    }

    Map<String,Object> query(int actor,JsonNode plan,String id) {
        LocalDate today=LocalDate.now(ZONE);
        if(plan.size()!=3 || !plan.path("tool").isTextual() || !TOOLS.contains(plan.path("tool").asText()) || !plan.path("start").isTextual() || !plan.path("end").isTextual())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"仅支持活动报名排行和每日时间币流转，请明确日期范围");
        LocalDate start,end;
        try { start=LocalDate.parse(plan.path("start").asText());end=LocalDate.parse(plan.path("end").asText()); }
        catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"模型日期格式不合法");}
        long days=ChronoUnit.DAYS.between(start,end);
        if(days<1 || days>31 || end.isAfter(today.plusDays(1)))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"查询范围须为历史1至31天，结束日期不包含在内");
        // 模型调用期间权限可能变化，每次真正使用查询工具前重新验证。
        authorization.requireAdmin(actor);
        String tool=plan.path("tool").asText(), scope, conclusion;
        List<Map<String,Object>> rows=new ArrayList<>();
        Timestamp from=Timestamp.valueOf(start.atStartOfDay()),to=Timestamp.valueOf(end.atStartOfDay());
        if("activity_registration_top".equals(tool)) {
            var result=jdbc.queryForList("select a.id as activity_id,a.title as title,count(*) as registrations from activity_volunteer av join activity a on a.id=av.activity_id where av.create_time>=? and av.create_time<? group by a.id,a.title order by registrations desc,a.id asc limit 10",from,to);
            for(var row:result)rows.add(Map.of("label",String.valueOf(row.get("title")),"activityId",row.get("activity_id"),"value",row.get("registrations").toString()));
            scope="按报名记录创建时间统计当前仍保留的报名关系，最多10项；已取消/删除报名不在其中，不是历史报名总量。";
            conclusion=rows.isEmpty()?"该范围内没有保留的报名记录。":"当前保留报名数排名第一的活动编号为"+rows.get(0).get("activityId")+"，报名数"+rows.get(0).get("value")+"；相同数量按活动编号排序。";
        } else {
            var result=jdbc.queryForList("select cast(create_time as date) as flow_day,sum(amount) as total from chain_tx_log where tx_type='TRANSFER' and create_time>=? and create_time<? group by cast(create_time as date) order by flow_day",from,to);
            Map<String,String> daily=new HashMap<>();for(var row:result)daily.put(row.get("flow_day").toString(),new BigDecimal(row.get("total").toString()).toPlainString());
            BigDecimal total=BigDecimal.ZERO;
            for(LocalDate day=start;day.isBefore(end);day=day.plusDays(1)){String amount=daily.getOrDefault(day.toString(),"0");rows.add(Map.of("label",day.toString(),"value",amount));total=total.add(new BigDecimal(amount));}
            scope="按业务数据库台账create_time统计已入账TRANSFER金额，包含发布费和答谢，排除MINT；无记录日显示0。不是链上全部交易、净流量或账户余额。";
            conclusion="该范围已入账转账记录金额合计"+total.toPlainString()+"时间币。";
        }
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("auditId",id);response.put("tool",tool);response.put("start",start.toString());response.put("end",end.toString());response.put("timezone",ZONE.toString());
        response.put("chartType","activity_registration_top".equals(tool)?"bar":"line");response.put("rows",rows);response.put("scope",scope);response.put("answer",conclusion);
        // 数值结论由工具结果构造，不让生成模型编造趋势或原因；记录问题摘要而非原始问题。
        return response;
    }
}
