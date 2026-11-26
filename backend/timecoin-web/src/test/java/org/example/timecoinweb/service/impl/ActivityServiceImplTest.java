package org.example.timecoinweb.service.impl;

import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.mapper.AdmiMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.example.timecoinweb.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActivityServiceImplTest {
    private ActivityServiceImpl service;
    private SettlementService settlements;
    private ActivityMapper activityMapper;
    private VolMapper volMapper;
    private AdmiMapper admiMapper;
    private OldMapper oldMapper;
    private BlockchainProperties blockchainProperties;

    @BeforeEach
    void setUp() {
        service = new ActivityServiceImpl();
        settlements = mock(SettlementService.class);
        activityMapper = mock(ActivityMapper.class);
        volMapper = mock(VolMapper.class);
        admiMapper = mock(AdmiMapper.class);
        oldMapper = mock(OldMapper.class);
        blockchainProperties = mock(BlockchainProperties.class);
        ReflectionTestUtils.setField(service, "settlements", settlements);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "volMapper", volMapper);
        ReflectionTestUtils.setField(service, "admiMapper", admiMapper);
        ReflectionTestUtils.setField(service, "oldMapper", oldMapper);
        ReflectionTestUtils.setField(service, "blockchainProperties", blockchainProperties);
    }

    private Activity activity(int oldId) {
        Activity a = new Activity();
        a.setOldId(oldId);
        a.setQuota((short) 10);
        return a;
    }

    private void stubOldIdValid(int raw, Integer oldPk) {
        when(oldMapper.selectUserId(raw)).thenReturn(oldPk != null ? raw : null);
        when(oldMapper.selectOldId(raw)).thenReturn(oldPk);
    }

    @Test
    void addSetsDefaultsAndInserts() {
        when(admiMapper.selectAdministratorId(1)).thenReturn(3);
        stubOldIdValid(5, 5);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(BigInteger.valueOf(999));
        Activity a = activity(5);

        service.add(a, 1);

        assertEquals(2, a.getStatus().intValue());
        assertEquals("other_service", a.getServiceType());
        assertEquals("{}", a.getExtraJson());
        assertEquals(0, a.getVolunteerReward().intValue());
        assertEquals(10, a.getRemain().intValue());
        verify(activityMapper).insert(a);
    }

    @Test
    void addRejectsNegativeReward() {
        when(admiMapper.selectAdministratorId(1)).thenReturn(3);
        stubOldIdValid(5, 5);
        Activity a = activity(5);
        a.setVolunteerReward(-1);
        assertThrows(IllegalArgumentException.class, () -> service.add(a, 1));
    }

    @Test
    void addRejectsRewardOverMax() {
        when(admiMapper.selectAdministratorId(1)).thenReturn(3);
        stubOldIdValid(5, 5);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(BigInteger.valueOf(999));
        Activity a = activity(5);
        a.setVolunteerReward(1000);
        assertThrows(IllegalArgumentException.class, () -> service.add(a, 1));
    }

    @Test
    void addResolvesOldIdFromUserId() {
        when(admiMapper.selectAdministratorId(1)).thenReturn(3);
        when(oldMapper.selectUserId(100)).thenReturn(null);
        when(oldMapper.selectOldId(100)).thenReturn(7);
        when(blockchainProperties.getVolunteerRewardMax()).thenReturn(BigInteger.valueOf(999));
        Activity a = activity(100);

        service.add(a, 1);

        assertEquals(7, a.getOldId());
    }

    @Test
    void addThrowsWhenOldIdInvalid() {
        when(admiMapper.selectAdministratorId(1)).thenReturn(3);
        when(oldMapper.selectUserId(100)).thenReturn(null);
        when(oldMapper.selectOldId(100)).thenReturn(null);
        Activity a = activity(100);
        assertThrows(IllegalStateException.class, () -> service.add(a, 1));
    }

    @Test
    void updateRejectsManualPendingStatus() {
        Activity locked = activity(5);
        locked.setId(1);
        when(activityMapper.lockById(1)).thenReturn(locked);
        Activity a = activity(5);
        a.setId(1);
        a.setStatus((short) 0);
        assertThrows(IllegalArgumentException.class, () -> service.update(a, 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getActivityStatsMapsStatusDistribution() {
        when(activityMapper.countActivitiesByStatus()).thenReturn(List.of(
                Map.of("act_status", "0", "status_cnt", 1),
                Map.of("act_status", "2", "status_cnt", 3),
                Map.of("status", "3", "count", 5)));
        when(activityMapper.countTotalEngagement()).thenReturn(100);

        Map<String, Object> stats = service.getActivityStats();

        Map<String, Object> dist = (Map<String, Object>) stats.get("statusDistribution");
        assertEquals(1L, dist.get("paymentPending"));
        assertEquals(3L, dist.get("approved"));
        assertEquals(5L, dist.get("ongoing"));
        assertEquals(100, stats.get("totalEngagement"));
    }
}
