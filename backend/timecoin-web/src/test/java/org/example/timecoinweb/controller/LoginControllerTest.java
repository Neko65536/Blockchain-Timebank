package org.example.timecoinweb.controller;

import org.example.pojo.LoginTokens;
import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.service.AgreementService;
import org.example.timecoinweb.service.RegisterService;
import org.example.timecoinweb.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginControllerTest {
    private LoginController controller;
    private RegisterService registerService;
    private TokenService tokenService;
    private AgreementService agreementService;

    @BeforeEach
    void setUp() {
        controller = new LoginController();
        registerService = mock(RegisterService.class);
        tokenService = mock(TokenService.class);
        agreementService = mock(AgreementService.class);
        ReflectionTestUtils.setField(controller, "registerService", registerService);
        ReflectionTestUtils.setField(controller, "tokenService", tokenService);
        ReflectionTestUtils.setField(controller, "agreementService", agreementService);
    }

    private User req(short role) {
        User u = new User();
        u.setUsername("test");
        u.setRole(role);
        return u;
    }

    private User db(short role) {
        User u = new User();
        u.setId(1);
        u.setUsername("test");
        u.setRole(role);
        u.setMustChangePassword((short) 0);
        return u;
    }

    @Test
    void loginSucceedsWhenRoleMatches() {
        User req = req((short) 1);
        User db = db((short) 1);
        when(registerService.login(any(User.class))).thenReturn(db);
        when(tokenService.accessToken(db)).thenReturn("access");
        when(tokenService.issueRefreshToken(1)).thenReturn("refresh");
        when(agreementService.needsReaccept(1)).thenReturn(false);

        Result r = controller.login(req);

        assertEquals(1, r.getCode().intValue());
        LoginTokens t = (LoginTokens) r.getData();
        assertEquals("access", t.getToken());
        assertEquals("refresh", t.getRefreshToken());
        assertEquals(1, t.getRole().intValue());
        assertEquals(0, t.getMustChangePassword().intValue());
    }

    @Test
    void loginFailsWhenUserNotFound() {
        when(registerService.login(any(User.class))).thenReturn(null);
        Result r = controller.login(req((short) 1));
        assertEquals(0, r.getCode().intValue());
        assertEquals("用户名或密码或角色错误", r.getMsg());
    }

    @Test
    void loginFailsWhenRoleMismatch() {
        when(registerService.login(any(User.class))).thenReturn(db((short) 2));
        Result r = controller.login(req((short) 1));
        assertEquals(0, r.getCode().intValue());
    }

    @Test
    void loginFailsWhenClientRoleIsNull() {
        User req = new User();
        req.setUsername("test");
        req.setRole(null);
        when(registerService.login(any(User.class))).thenReturn(db((short) 1));
        Result r = controller.login(req);
        assertEquals(0, r.getCode().intValue());
    }

    @Test
    void loginSucceedsWithRoleZeroForLegacyClient() {
        User req = req((short) 0);
        User db = db((short) 2);
        when(registerService.login(any(User.class))).thenReturn(db);
        when(tokenService.accessToken(db)).thenReturn("access");
        when(tokenService.issueRefreshToken(1)).thenReturn("refresh");
        when(agreementService.needsReaccept(1)).thenReturn(true);

        Result r = controller.login(req);

        assertEquals(1, r.getCode().intValue());
        LoginTokens t = (LoginTokens) r.getData();
        assertEquals(2, t.getRole().intValue());
        assertEquals(1, t.getAgreementCurrent().intValue());
    }
}
