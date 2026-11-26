package org.example.timecoinweb.controller;

import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.mapper.RegisterMapper;
import org.example.timecoinweb.service.BalanceReadCache;
import org.example.timecoinweb.service.PasswordService;
import org.example.timecoinweb.service.SettlementService;
import org.example.timecoinweb.service.TimeCoinChainService;
import org.example.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserCoinControllerTest {
    private UserCoinController controller;
    private TimeCoinChainService timeCoinChainService;
    private RegisterMapper registerMapper;
    private PasswordService passwordService;
    private SettlementService settlements;
    private BalanceReadCache balanceCache;

    private String token(int id) {
        JwtUtils.configureSecret("test-secret-0123456789abcdef0123456789abcdef");
        return JwtUtils.generateJwt(Map.of("id", id, "role", 2));
    }

    private UserCoinController.TransferRequest req(String to, String amount, String pwd) {
        UserCoinController.TransferRequest r = new UserCoinController.TransferRequest();
        r.setToUserId(to);
        r.setAmount(amount);
        r.setPassword(pwd);
        return r;
    }

    @BeforeEach
    void setUp() {
        controller = new UserCoinController();
        timeCoinChainService = mock(TimeCoinChainService.class);
        registerMapper = mock(RegisterMapper.class);
        passwordService = mock(PasswordService.class);
        settlements = mock(SettlementService.class);
        balanceCache = mock(BalanceReadCache.class);
        ReflectionTestUtils.setField(controller, "timeCoinChainService", timeCoinChainService);
        ReflectionTestUtils.setField(controller, "registerMapper", registerMapper);
        ReflectionTestUtils.setField(controller, "passwordService", passwordService);
        ReflectionTestUtils.setField(controller, "settlements", settlements);
        ReflectionTestUtils.setField(controller, "balanceCache", balanceCache);
    }

    private User currentUser(String pwd) {
        User u = new User();
        u.setId(1);
        u.setUsername("u1");
        u.setPassword(pwd);
        return u;
    }

    @Test
    void transferRejectsMissingFields() {
        Result r = controller.transfer(token(1), req(null, "10", "pwd"), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("请提供收款用户ID和转账金额", r.getMsg());
    }

    @Test
    void transferRejectsEmptyPassword() {
        Result r = controller.transfer(token(1), req("2", "10", " "), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("请输入登录密码进行验证", r.getMsg());
    }

    @Test
    void transferRejectsUnknownUser() {
        when(registerMapper.getById(1)).thenReturn(null);
        Result r = controller.transfer(token(1), req("2", "10", "pwd"), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("用户不存在", r.getMsg());
    }

    @Test
    void transferRejectsWrongPassword() {
        when(registerMapper.getById(1)).thenReturn(currentUser("$2a$10$hash"));
        when(passwordService.matches("pwd", "$2a$10$hash")).thenReturn(false);
        Result r = controller.transfer(token(1), req("2", "10", "pwd"), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("密码错误", r.getMsg());
    }

    @Test
    void transferUpgradesLegacyPlaintextPassword() {
        when(registerMapper.getById(1)).thenReturn(currentUser("plaintext"));
        when(passwordService.matches("pwd", "plaintext")).thenReturn(true);
        when(passwordService.needsUpgrade("plaintext")).thenReturn(true);
        when(passwordService.encode("pwd")).thenReturn("$2a$10$new");
        when(settlements.submitTransfer(eq(1), eq("req-1"), eq("USER_TRANSFER"), eq("1"), eq("2"), any(BigInteger.class)))
                .thenReturn(Map.of("state", "PENDING"));

        Result r = controller.transfer(token(1), req("2", "10", "pwd"), "req-1");

        assertEquals(1, r.getCode().intValue());
        verify(registerMapper).updatePasswordById(1, "$2a$10$new");
    }

    @Test
    void transferRejectsNonPositiveAmount() {
        when(registerMapper.getById(1)).thenReturn(currentUser("$2a$10$hash"));
        when(passwordService.matches("pwd", "$2a$10$hash")).thenReturn(true);
        Result r = controller.transfer(token(1), req("2", "0", "pwd"), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("转账金额必须大于0", r.getMsg());
    }

    @Test
    void transferRejectsSelfTransfer() {
        when(registerMapper.getById(1)).thenReturn(currentUser("$2a$10$hash"));
        when(passwordService.matches("pwd", "$2a$10$hash")).thenReturn(true);
        Result r = controller.transfer(token(1), req("1", "10", "pwd"), "req-1");
        assertEquals(0, r.getCode().intValue());
        assertEquals("不能转账给自己", r.getMsg());
    }

    @Test
    void transferSucceeds() {
        when(registerMapper.getById(1)).thenReturn(currentUser("$2a$10$hash"));
        when(passwordService.matches("pwd", "$2a$10$hash")).thenReturn(true);
        when(passwordService.needsUpgrade("$2a$10$hash")).thenReturn(false);
        when(settlements.submitTransfer(eq(1), eq("req-1"), eq("USER_TRANSFER"), eq("1"), eq("2"), any(BigInteger.class)))
                .thenReturn(Map.of("state", "COMPLETED"));

        Result r = controller.transfer(token(1), req("2", "10", "pwd"), "req-1");

        assertEquals(1, r.getCode().intValue());
        assertEquals("COMPLETED", ((Map<?, ?>) r.getData()).get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void balanceZeroWhenChainNotReady() {
        when(timeCoinChainService.isChainReady()).thenReturn(false);
        when(timeCoinChainService.getNotReadyReason()).thenReturn("未启用");

        Result r = controller.getMyBalance(token(1));

        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals("0", body.get("balance"));
        assertEquals(false, body.get("chainReady"));
    }

    @Test
    void historyClampsLimitToRange() throws Exception {
        when(timeCoinChainService.listUserMintTransferEvents(eq("1"), anyInt())).thenReturn(java.util.List.of());
        Result r = controller.getTransferHistory(token(1), 9999);
        assertEquals(1, r.getCode().intValue());
        verify(timeCoinChainService).listUserMintTransferEvents("1", 200);
    }
}
