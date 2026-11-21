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
        if (storedUser == null || !passwordService.matches(user.getPassword(), storedUser.getPassword())) {
            return null;
        }
        // J4：冻结账号禁止登录
        if (storedUser.getStatus() != null && storedUser.getStatus() == 0) {
            return null;
        }
        if (passwordService.needsUpgrade(storedUser.getPassword())) {
            String encodedPassword = passwordService.encode(user.getPassword());
            registerMapper.updatePasswordById(storedUser.getId(), encodedPassword);
            storedUser.setPassword(encodedPassword);
        }
        return storedUser;
    }

    @Override
    public User selectSelf(Integer id) {
        User user=registerMapper.getById(id);

        return user;
    }
}
