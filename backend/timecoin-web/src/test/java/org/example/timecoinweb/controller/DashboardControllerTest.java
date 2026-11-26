package org.example.timecoinweb.controller;

import org.example.pojo.Result;
import org.example.timecoinweb.service.ActivityService;
import org.example.timecoinweb.service.NoticeService;
import org.example.timecoinweb.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardControllerTest {
    private DashboardController controller;
    private UserService userService;
    private ActivityService activityService;
    private NoticeService noticeService;

    @BeforeEach
    void setUp() {
        controller = new DashboardController();
        userService = mock(UserService.class);
        activityService = mock(ActivityService.class);
        noticeService = mock(NoticeService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "activityService", activityService);
        ReflectionTestUtils.setField(controller, "noticeService", noticeService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statsReturnsUserAndActivityStats() {
        when(userService.getUserStats()).thenReturn(Map.of("total", 10));
        when(activityService.getActivityStats()).thenReturn(Map.of("active", 3));

        Result r = controller.getHomeStats();

        assertEquals(1, r.getCode().intValue());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(Map.of("total", 10), data.get("userStats"));
        assertEquals(Map.of("active", 3), data.get("activityStats"));
    }

    @Test
    void noticesReturnsList() {
        when(noticeService.listRecent(30)).thenReturn(List.of());

        Result r = controller.listNotices(30);

        assertEquals(1, r.getCode().intValue());
        assertEquals(List.of(), r.getData());
    }
}
