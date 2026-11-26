package org.example.timecoinweb.service.impl;

import org.example.pojo.User;
import org.example.timecoinweb.mapper.AdmiMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.example.timecoinweb.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManageUserServiceImplTest {
    private ManageUserServiceImpl service;
    private UserMapper userMapper;
    private VolMapper volMapper;
    private AdmiMapper admiMapper;
    private OldMapper oldMapper;
    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        service = new ManageUserServiceImpl();
        userMapper = mock(UserMapper.class);
        volMapper = mock(VolMapper.class);
        admiMapper = mock(AdmiMapper.class);
        oldMapper = mock(OldMapper.class);
        passwordService = mock(PasswordService.class);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "volMapper", volMapper);
        ReflectionTestUtils.setField(service, "admiMapper", admiMapper);
        ReflectionTestUtils.setField(service, "oldMapper", oldMapper);
        ReflectionTestUtils.setField(service, "passwordService", passwordService);
    }

    @Test
    void updateEncodesPasswordWhenPresent() {
        User u = new User();
        u.setId(1);
        u.setPassword("plaintext");
        when(passwordService.encodeIfNecessary("plaintext")).thenReturn("$2a$10$hash");

        service.update(u);

        assertEquals("$2a$10$hash", u.getPassword());
        assertNotNull(u.getUpdateTime());
        verify(userMapper).update(u);
    }

    @Test
    void updateSkipsPasswordWhenBlank() {
        User u = new User();
        u.setId(1);
        u.setPassword(null);

        service.update(u);

        verify(passwordService, never()).encodeIfNecessary(anyString());
        verify(userMapper).update(u);
    }

    @Test
    void deleteByIdDelegatesToMappers() {
        service.deleteById(List.of(1, 2));
        verify(oldMapper).deleteByUserIds(List.of(1, 2));
        verify(admiMapper).deleteByUserIds(List.of(1, 2));
        verify(volMapper).deleteByUserIds(List.of(1, 2));
        verify(userMapper).deleteById(List.of(1, 2));
    }
}
