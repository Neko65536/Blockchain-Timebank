package org.example.timecoinweb.service;

import org.example.pojo.User;
import org.example.timecoinweb.mapper.AdmiMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.RegisterMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.service.impl.RegisterServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** N2 登录失败锁定：达阈值锁定、锁定期内拒绝、成功后清零；dev 默认关闭开关。 */
class LoginLockTests {
    RegisterServiceImpl service;
    RegisterMapper registerMapper;
    UserMapper userMapper;
    PasswordService passwordService;
    AgreementService agreementService;

    @BeforeEach
    void setup() {
        registerMapper = mock(RegisterMapper.class);
        userMapper = mock(UserMapper.class);
        passwordService = mock(PasswordService.class);
        agreementService = mock(AgreementService.class);
        service = new RegisterServiceImpl();
        ReflectionTestUtils.setField(service, "registerMapper", registerMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "passwordService", passwordService);
        ReflectionTestUtils.setField(service, "agreementService", agreementService);
        ReflectionTestUtils.setField(service, "loginLockEnabled", true);
        ReflectionTestUtils.setField(service, "maxFailedAttempts", 3);
        ReflectionTestUtils.setField(service, "lockMinutes", 15);
    }

    private User stored() {
        User u = new User();
        u.setId(7); u.setUsername("u"); u.setPassword("encoded"); u.setRole((short) 1);
        u.setFailedAttempts(0); u.setStatus((short) 1);
        return u;
    }

    @Test
    void threeFailuresLockAccount() {
        when(registerMapper.getByUsername("u")).thenReturn(stored());
        when(passwordService.matches(anyString(), anyString())).thenReturn(false);
        User login = new User(); login.setUsername("u"); login.setPassword("bad");
        for (int i = 0; i < 3; i++) assertNull(service.login(login));
        verify(userMapper, times(3)).updateLoginFailure(eq(7), anyInt(), any());
        // 第 3 次应写入锁定截止时间
        verify(userMapper, atLeastOnce()).updateLoginFailure(eq(7), eq(3), argThat(t -> t != null && t.isAfter(LocalDateTime.now())));
    }

    @Test
    void lockedAccountRejectsEvenCorrectPassword() {
        User stored = stored();
        stored.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(registerMapper.getByUsername("u")).thenReturn(stored);
        when(passwordService.matches(anyString(), anyString())).thenReturn(true);
        User login = new User(); login.setUsername("u"); login.setPassword("correct");
        assertNull(service.login(login));
        verify(userMapper, never()).clearLoginFailureAndForceFlag(anyInt());
    }

    @Test
    void successClearsFailures() {
        when(registerMapper.getByUsername("u")).thenReturn(stored());
        when(passwordService.matches(anyString(), anyString())).thenReturn(true);
        when(passwordService.needsUpgrade(anyString())).thenReturn(false);
        User login = new User(); login.setUsername("u"); login.setPassword("correct");
        assertNotNull(service.login(login));
        verify(userMapper).clearLoginFailureAndForceFlag(7);
    }

    @Test
    void unknownUserDoesNotTouchCounters() {
        when(registerMapper.getByUsername("u")).thenReturn(null);
        User login = new User(); login.setUsername("u"); login.setPassword("bad");
        assertNull(service.login(login));
        verifyNoInteractions(userMapper);
    }
}
