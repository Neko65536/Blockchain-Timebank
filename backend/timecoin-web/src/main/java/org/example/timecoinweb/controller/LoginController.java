package org.example.timecoinweb.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.LoginTokens;
import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.service.RegisterService;
import org.example.timecoinweb.service.TokenService;
import org.example.timecoinweb.service.AgreementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class LoginController {

    /**
     * 登录功能
     */
    @Autowired
    private RegisterService registerService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private AgreementService agreementService;


    @PostMapping("/login")
    public Result login(@RequestBody User user){
        //用service检查是否在数据库
        log.info("登录账号:{}", user.getUsername());
        User u=registerService.login(user);

        // 登录成功条件：账号密码已匹配(u!=null)，且
        // - 客户端 role 与库中角色一致（1老人 2志愿者 3管理员），或
        // - role==0：兼容移动端仅校验用户名密码、以库中角色签发令牌
        if (u != null && loginRoleMatches(user.getRole(), u.getRole())) {
            String access = tokenService.accessToken(u);
            String refresh = tokenService.issueRefreshToken(u.getId());
            LoginTokens tokens = new LoginTokens(access, refresh, u.getRole(),
                    u.getMustChangePassword() == null ? 0 : u.getMustChangePassword().intValue(),
                    agreementService.needsReaccept(u.getId()) ? 1 : 0);
            return Result.success(tokens);
        }

        //登录失败
        return Result.error("用户名或密码或角色错误");
    }

    /** 客户端选择的角色是否与库中一致；0 表示不校验角色（移动端历史行为） */
    private static boolean loginRoleMatches(Short clientRole, Short dbRole) {
        if (clientRole == null) {
            return false;
        }
        if (clientRole.shortValue() == 0) {
            return true;
        }
        return dbRole != null && clientRole.equals(dbRole);
    }
}
