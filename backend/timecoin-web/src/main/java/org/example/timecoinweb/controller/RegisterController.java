package org.example.timecoinweb.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class RegisterController {

    @Autowired
    private RegisterService registerService;
    @Autowired
    private org.example.timecoinweb.service.AgreementService agreementService;

    /**
     * 注册
     * @return
     */
    @PostMapping("/register")
    public Result register(@RequestBody User user){
        if (user.getRole() == null || (user.getRole() != 1 && user.getRole() != 2)) {
            return Result.error("公开注册仅支持老人和志愿者；管理员账号须由现有管理员创建");
        }
        // 显式同意协议时版本必须与当前生效版本一致；未声明同意则保持旧注册行为（不记录同意）。
        if (Boolean.TRUE.equals(user.getAgreed())) {
            try {
                if (!agreementService.current().get("version").equals(user.getAgreementVersion()))
                    return Result.error("协议版本已更新，请重新阅读并同意最新版本");
            } catch (IllegalStateException e) {
                return Result.error("协议未配置，无法注册");
            }
        }
        //用service保存到数据库
        String msg=registerService.register(user);
        log.info("注册新用户");

        if(msg.equals("1")){
            return Result.success();
        }

        return Result.error(msg);
    }
}
