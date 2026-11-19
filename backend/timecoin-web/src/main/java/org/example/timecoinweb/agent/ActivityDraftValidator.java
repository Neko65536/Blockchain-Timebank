package org.example.timecoinweb.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.util.ServiceTypeSupport;
import org.springframework.stereotype.Component;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;

/** Agent只处理可编辑表单字段；身份、审核状态和支付字段不得由模型指定。 */
@Component
@RequiredArgsConstructor
public class ActivityDraftValidator {
    private static final Set<String> FIELDS = Set.of("title", "quota", "deadline", "date", "begin", "end",
            "address", "phone", "description", "serviceType", "extraJson", "volunteerReward");
    private final ObjectMapper json;
    private final BlockchainProperties blockchain;

    public Activity decode(JsonNode fields) {
        if (fields == null || !fields.isObject()) throw new IllegalArgumentException("活动草稿必须为对象");
        if (fields.toString().length() > 16000) throw new IllegalArgumentException("活动草稿过长");
        fields.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) throw new IllegalArgumentException("活动草稿包含未授权字段：" + name);
            if (!Set.of("quota", "volunteerReward").contains(name) && !fields.get(name).isNull() && !fields.get(name).isTextual())
                throw new IllegalArgumentException(name + "必须为文本");
        });
        for (String field : List.of("quota", "volunteerReward")) {
            JsonNode n = fields.get(field);
            if (n != null && !n.isNull() && (!n.isIntegralNumber() || !n.canConvertToInt()
                    || (field.equals("quota") && (n.intValue() < 1 || n.intValue() > Short.MAX_VALUE))))
                throw new IllegalArgumentException(field + "必须为有效整数");
        }
        try { return json.treeToValue(fields, Activity.class); }
        catch (Exception e) { throw new IllegalArgumentException("活动字段类型或日期时间格式错误"); }
    }

    public List<String> issues(Activity a, LocalDateTime now) {
        List<String> errors = new ArrayList<>();
        text(errors, "title", a.getTitle(), 200);
        text(errors, "description", a.getDescription(), 4000);
        text(errors, "address", a.getAddress(), 255);
        text(errors, "phone", a.getPhone(), 32);
        if (a.getQuota() == null || a.getQuota() < 1) errors.add("quota：请填写正整数名额");
        if (a.getDate() == null) errors.add("date：请补充活动日期");
        if (a.getBegin() == null) errors.add("begin：请补充开始时间");
        if (a.getEnd() == null) errors.add("end：请补充结束时间");
        if (a.getBegin() != null && a.getEnd() != null && !a.getEnd().isAfter(a.getBegin()))
            errors.add("end：结束时间必须晚于开始时间");
        if (a.getDeadline() == null) errors.add("deadline：请补充报名截止时间");
        else {
            if (!a.getDeadline().isAfter(now)) errors.add("deadline：截止时间必须在未来");
            if (a.getDate() != null && a.getBegin() != null && a.getDeadline().isAfter(a.getDate().atTime(a.getBegin())))
                errors.add("deadline：截止时间不能晚于活动开始");
        }
        if (a.getDate() != null && a.getBegin() != null && !a.getDate().atTime(a.getBegin()).isAfter(now))
            errors.add("date：活动开始时间必须在未来");
        Integer reward = a.getVolunteerReward();
        BigInteger max = blockchain.getVolunteerRewardMax();
        if (reward == null || reward < 0 || (max != null && max.signum() > 0 && BigInteger.valueOf(reward).compareTo(max) > 0))
            errors.add("volunteerReward：请明确填写配置上限内的非负整数，Agent不自动授权答谢");
        try { ServiceTypeSupport.normalizeForCreate(a, json); }
        catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        return errors;
    }

    private void text(List<String> errors, String field, String value, int max) {
        if (value == null || value.isBlank()) errors.add(field + "：请补充");
        else if (value.length() > max) errors.add(field + "：内容过长");
    }
}
