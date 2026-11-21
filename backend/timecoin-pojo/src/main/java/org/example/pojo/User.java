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

    /** N1：令牌版本；登出/改密/冻结时递增，旧 token 立即失效。 */
    private Integer tokenVersion;
    /** N2：连续登录失败次数。 */
    private Integer failedAttempts;
    /** N2：账户锁定截止时间（字符串由查询层转换）。 */
    private java.time.LocalDateTime lockedUntil;
    /** N2：1 表示管理员首次登录须改密。 */
    private Short mustChangePassword;
}
