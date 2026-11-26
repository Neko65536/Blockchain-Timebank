package org.example.timecoinweb.controller;

import org.example.pojo.Notice;
import org.example.pojo.Result;
import org.example.timecoinweb.service.NoticeCacheService;
import org.example.timecoinweb.service.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoticeAdminControllerTest {
    private NoticeAdminController controller;
    private NoticeService noticeService;
    private NoticeCacheService cache;

    @BeforeEach
    void setUp() {
        controller = new NoticeAdminController();
        noticeService = mock(NoticeService.class);
        cache = mock(NoticeCacheService.class);
        ReflectionTestUtils.setField(controller, "noticeService", noticeService);
        ReflectionTestUtils.setField(controller, "cache", cache);
    }

    @Test
    void cacheMetricsReturnsMetrics() {
        when(cache.metrics()).thenReturn(Map.of("requests", 10L));
        Result r = controller.cacheMetrics();
        assertEquals(1, r.getCode().intValue());
        assertEquals(Map.of("requests", 10L), r.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsNotices() {
        when(noticeService.listAll()).thenReturn(List.of(new Notice()));
        Result r = controller.list();
        assertEquals(1, r.getCode().intValue());
        assertEquals(1, ((List<Notice>) r.getData()).size());
    }

    @Test
    void addRejectsEmptyContent() {
        Notice n = new Notice();
        n.setContent("   ");
        Result r = controller.add(n);
        assertEquals(0, r.getCode().intValue());
        assertEquals("公告正文不能为空", r.getMsg());
        verifyNoInteractions(noticeService);
    }

    @Test
    void addDefaultsTitleAndSucceeds() {
        Notice n = new Notice();
        n.setContent("内容");
        Result r = controller.add(n);
        assertEquals(1, r.getCode().intValue());
        verify(noticeService).add(n);
        assertEquals("", n.getTitle());
    }

    @Test
    void updateRejectsMissingId() {
        Notice n = new Notice();
        n.setContent("内容");
        Result r = controller.update(n);
        assertEquals(0, r.getCode().intValue());
        assertEquals("缺少公告 id", r.getMsg());
        verifyNoInteractions(noticeService);
    }

    @Test
    void updateSucceeds() {
        Notice n = new Notice();
        n.setId(1);
        n.setContent("内容");
        Result r = controller.update(n);
        assertEquals(1, r.getCode().intValue());
        verify(noticeService).update(n);
    }

    @Test
    void deleteCallsService() {
        Result r = controller.delete(5);
        assertEquals(1, r.getCode().intValue());
        verify(noticeService).deleteById(5);
    }
}
