package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class User {
    private Integer id;
    private String username;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @lombok.ToString.Exclude
    private String password;
    private Short role;
    private String email;
    private Short age;
    private String phone;
    private String address;

    private String name;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String image;

    /** J4：账号状态，1 正常 / 0 冻结 */
    private Short status;

    /** 时间币余额镜像（与链上 balanceOf 对账，链为权威数据源） */
    private Long coinBalance;
    /** 最近一次对账/同步时间 */
    private LocalDateTime balanceSyncTime;

    /** 注册传输字段（不落 user 表）：用户同意的协议版本号。 */
    private String agreementVersion;
    /** 注册传输字段（不落 user 表）：是否同意协议。 */
    private Boolean agreed;
}
