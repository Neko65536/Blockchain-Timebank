package org.example.timecoinweb.controller;

import org.example.pojo.Result;
import org.example.pojo.User;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.RegisterService;
import org.example.timecoinweb.service.TimeCoinChainService;
import org.example.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelfControllerTest {
    private SelfController controller;
    private RegisterService registerService;
    private TimeCoinChainService timeCoinChainService;
    private BlockchainProperties blockchainProperties;

    private String token(int id) {
        JwtUtils.configureSecret("test-secret-0123456789abcdef0123456789abcdef");
        return JwtUtils.generateJwt(Map.of("id", id, "role", 2));
    }

    @BeforeEach
    void setUp() {
        controller = new SelfController();
        registerService = mock(RegisterService.class);
        timeCoinChainService = mock(TimeCoinChainService.class);
        blockchainProperties = mock(BlockchainProperties.class);
        ReflectionTestUtils.setField(controller, "registerService", registerService);
        ReflectionTestUtils.setField(controller, "timeCoinChainService", timeCoinChainService);
        ReflectionTestUtils.setField(controller, "blockchainProperties", blockchainProperties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishActivityFeeDefaultsZeroWhenCostNull() {
        when(blockchainProperties.getOldPublishActivityCost()).thenReturn(null);
        when(blockchainProperties.isEnabled()).thenReturn(false);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(null);
        when(blockchainProperties.getFeeRecipientUserId()).thenReturn("platform");

        Result r = controller.publishActivityFee();

        assertEquals(1, r.getCode().intValue());
        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals("0", body.get("cost"));
        assertEquals(false, body.get("deductEnabled"));
        assertEquals(null, body.get("volunteerRewardMax"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishActivityFeeDeductsWhenChainReadyAndCostPositive() {
        when(blockchainProperties.getOldPublishActivityCost()).thenReturn(BigInteger.valueOf(4));
        when(blockchainProperties.isEnabled()).thenReturn(true);
        when(timeCoinChainService.isChainReady()).thenReturn(true);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(BigInteger.valueOf(999));
        when(blockchainProperties.getFeeRecipientUserId()).thenReturn("platform");

        Result r = controller.publishActivityFee();

        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals("4", body.get("cost"));
        assertEquals(true, body.get("deductEnabled"));
        assertEquals(true, body.get("volunteerRewardMaxCapped"));
        assertEquals("999", body.get("volunteerRewardMax"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishActivityFeeNoDeductWhenChainNotReady() {
        when(blockchainProperties.getOldPublishActivityCost()).thenReturn(BigInteger.valueOf(4));
        when(blockchainProperties.isEnabled()).thenReturn(true);
        when(timeCoinChainService.isChainReady()).thenReturn(false);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(null);
        when(blockchainProperties.getFeeRecipientUserId()).thenReturn("platform");

        Result r = controller.publishActivityFee();

        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals(false, body.get("deductEnabled"));
        assertEquals(false, body.get("volunteerRewardMaxCapped"));
    }

    @Test
    void selectSelfReturnsUser() {
        User u = new User();
        u.setId(1);
        u.setUsername("old");
        when(registerService.selectSelf(1)).thenReturn(u);

        Result r = controller.selectSelf(token(1));

        assertEquals(1, r.getCode().intValue());
        assertSame(u, r.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void coinBalanceZeroWhenChainNotReady() {
        when(timeCoinChainService.isChainReady()).thenReturn(false);
        when(timeCoinChainService.getNotReadyReason()).thenReturn("链未启用");

        Result r = controller.coinBalance(token(7));

        assertEquals(1, r.getCode().intValue());
        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals("7", body.get("userId"));
        assertEquals("0", body.get("balance"));
        assertEquals(Boolean.FALSE, body.get("chainReady"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coinBalanceReadsChain() throws Exception {
        when(timeCoinChainService.isChainReady()).thenReturn(true);
        when(timeCoinChainService.balanceOf("7")).thenReturn(BigInteger.valueOf(88));

        Result r = controller.coinBalance(token(7));

        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertEquals("88", body.get("balance"));
        assertEquals(Boolean.TRUE, body.get("chainReady"));
    }
}
