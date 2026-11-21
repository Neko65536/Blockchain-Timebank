package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CompensationRecord {
    Integer id;
    Integer activityId;
    Integer volunteerTableId;
    /** J5：链上扣回所需的志愿者系统账户 id */
    Integer volunteerUserId;
    Integer elderUserId;
    Integer amount;
    /** 0 待追讨 / 1 已追讨 / 2 平台核销 */
    Short status;
    LocalDateTime createTime;
    LocalDateTime updateTime;
}
