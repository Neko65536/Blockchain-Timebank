package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** N1 登录响应：access token + refresh token + 角色，附加首登改密标志。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginTokens {
    private String token;
    private String refreshToken;
    private Short role;
    /** N2：1 表示管理员首次登录须改密 */
    private Integer mustChangePassword;
    /** N3：0 表示用户尚未同意当前生效协议，前端须引导重新同意 */
    private Integer agreementCurrent;
}
