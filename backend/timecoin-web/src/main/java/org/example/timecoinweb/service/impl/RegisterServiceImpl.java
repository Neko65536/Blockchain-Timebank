package org.example.timecoinweb.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.User;
import org.example.timecoinweb.mapper.AdmiMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.RegisterMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.example.timecoinweb.service.PasswordService;
import org.example.timecoinweb.service.RegisterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService{

    @Autowired
    private RegisterMapper registerMapper;
    @Autowired
    private AdmiMapper admiMapper;
    @Autowired
    private OldMapper oldMapper;
    @Autowired
    private VolMapper volMapper;
    @Autowired
    private PasswordService passwordService;
    @Autowired
    private org.example.timecoinweb.service.AgreementService agreementService;
    @Autowired
    private org.example.timecoinweb.mapper.UserMapper userMapper;

    /** N2：登录失败锁定开关，dev 默认关闭以免干扰回归测试，生产开启。 */
    @org.springframework.beans.factory.annotation.Value("${app.auth.login-lock-enabled:false}")
    private boolean loginLockEnabled;
    /** N2：连续失败阈值 */
    @org.springframework.beans.factory.annotation.Value("${app.auth.max-failed-attempts:5}")
    private int maxFailedAttempts;
    /** N2：锁定时长（分钟） */
    @org.springframework.beans.factory.annotation.Value("${app.auth.lock-minutes:15}")
    private int lockMinutes;


    @Transactional
    @Override
    public String register(User user) {
        if (user == null || user.getRole() == null || user.getRole() < 1 || user.getRole() > 3
                || !StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
            throw new IllegalArgumentException("用户名、密码和角色不合法");
        }
        //调用对应的registermapper
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        user.setPassword(passwordService.encode(user.getPassword()));

        //将user存入到user表中
        try {
            registerMapper.register(user);
        }catch (DuplicateKeyException e){
            log.info("注册的用户名重复");
            return "用户名重复";
        }


        //提取出user,为了把他存入老人、志愿者、管理员表
        User user2=registerMapper.getByUsername(user.getUsername());
        if (user2 == null) {
            return "注册失败，请稍后重试";
        }

        switch (user2.getRole()){
            case 1:
                oldMapper.insertByUserId(user2.getId());
                break;
            case 2:
                volMapper.insertByUserId(user2.getId());
                break;
            case 3:
                admiMapper.insertByUserId(user2.getId());
                break;
        }
        // 协议同意与账号创建在同一事务内落库；版本号由客户端对照 /agreement/current 提交。
        agreementService.recordAcceptance(user2.getId(), user.getAgreementVersion(),
                Boolean.TRUE.equals(user.getAgreed()), "register");

        return "1";
    }

    @Override
    public User login(User user) {
        if (user == null || !StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
            return null;
        }
        User storedUser = registerMapper.getByUsername(user.getUsername());
        // 用户名不存在时也做一次伪校验，避免响应时间差泄露用户名
        boolean passwordOk = storedUser != null && passwordService.matches(user.getPassword(), storedUser.getPassword());
        if (!passwordOk) {
            if (storedUser != null && loginLockEnabled) recordFailure(storedUser);
            return null;
        }
        // J4：冻结账号禁止登录
        if (storedUser.getStatus() != null && storedUser.getStatus() == 0) {
            return null;
        }
        // N2：账户锁定期内禁止登录（含密码正确的情况）
        if (loginLockEnabled && storedUser.getLockedUntil() != null
                && storedUser.getLockedUntil().isAfter(LocalDateTime.now())) {
            return null;
        }
        // 登录成功后清零失败计数（首登强制改密标志由改密接口单独清除）
        if (loginLockEnabled) {
            userMapper.clearLoginFailureAndForceFlag(storedUser.getId());
            storedUser.setFailedAttempts(0);
            storedUser.setLockedUntil(null);
        }
        if (passwordService.needsUpgrade(storedUser.getPassword())) {
            String encodedPassword = passwordService.encode(user.getPassword());
            registerMapper.updatePasswordById(storedUser.getId(), encodedPassword);
            storedUser.setPassword(encodedPassword);
        }
        return storedUser;
    }

    /** N2：失败计数达阈值后锁定账户；锁定期间连续失败延长时长。 */
    private void recordFailure(User user) {
        int prev = user.getFailedAttempts() == null ? 0 : user.getFailedAttempts();
        int next = prev + 1;
        LocalDateTime lockUntil = null;
        if (next >= maxFailedAttempts) {
            lockUntil = LocalDateTime.now().plusMinutes(lockMinutes);
        }
        userMapper.updateLoginFailure(user.getId(), next, lockUntil);
        user.setFailedAttempts(next);
        user.setLockedUntil(lockUntil);
    }

    @Override
    public void updatePassword(Integer id, String encodedPassword) {
        registerMapper.updatePasswordById(id, encodedPassword);
    }

    @Override
    public User selectSelf(Integer id) {
        User user=registerMapper.getById(id);

        return user;
    }
}
