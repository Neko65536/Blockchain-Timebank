package org.example.timecoinweb.controller;

import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.service.AgreementService;
import org.example.timecoinweb.service.RegisterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegisterControllerTest {
    private RegisterController controller;
    private RegisterService registerService;
    private AgreementService agreementService;

    @BeforeEach
    void setUp() {
        controller = new RegisterController();
        registerService = mock(RegisterService.class);
        agreementService = mock(AgreementService.class);
        ReflectionTestUtils.setField(controller, "registerService", registerService);
        ReflectionTestUtils.setField(controller, "agreementService", agreementService);
        ReflectionTestUtils.setField(controller, "agreementRequired", false);
    }

    private User req(short role) {
        User u = new User();
        u.setUsername("newuser");
        u.setRole(role);
        return u;
    }

    @Test
    void registerRejectsNullRole() {
        Result r = controller.register(new User());
        assertEquals(0, r.getCode().intValue());
        assertTrue(r.getMsg().contains("公开注册仅支持老人和志愿者"));
        verifyNoInteractions(registerService);
    }

    @Test
    void registerRejectsAdminRole() {
        Result r = controller.register(req((short) 3));
        assertEquals(0, r.getCode().intValue());
        verifyNoInteractions(registerService);
    }

    @Test
    void registerRequiresAgreementWhenEnabled() {
        ReflectionTestUtils.setField(controller, "agreementRequired", true);
        Result r = controller.register(req((short) 1));
        assertEquals(0, r.getCode().intValue());
        assertEquals("请先阅读并同意用户协议", r.getMsg());
        verifyNoInteractions(registerService);
    }

    @Test
    void registerRejectsOutdatedAgreementVersion() {
        User req = req((short) 1);
        req.setAgreed(true);
        req.setAgreementVersion("v1");
        when(agreementService.current()).thenReturn(Map.of("version", "v2"));
        Result r = controller.register(req);
        assertEquals(0, r.getCode().intValue());
        assertEquals("协议版本已更新，请重新阅读并同意最新版本", r.getMsg());
        verifyNoInteractions(registerService);
    }

    @Test
    void registerRejectsWhenAgreementMissing() {
        User req = req((short) 1);
        req.setAgreed(true);
        when(agreementService.current()).thenThrow(new IllegalStateException("未配置"));
        Result r = controller.register(req);
        assertEquals(0, r.getCode().intValue());
        assertEquals("协议未配置，无法注册", r.getMsg());
    }

    @Test
    void registerSucceeds() {
        when(registerService.register(any(User.class))).thenReturn("1");
        Result r = controller.register(req((short) 1));
        assertEquals(1, r.getCode().intValue());
    }

    @Test
    void registerReturnsBusinessError() {
        when(registerService.register(any(User.class))).thenReturn("用户名已存在");
        Result r = controller.register(req((short) 2));
        assertEquals(0, r.getCode().intValue());
        assertEquals("用户名已存在", r.getMsg());
    }
}
