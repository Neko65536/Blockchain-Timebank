package org.example.timecoinweb;

import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.mapper.NoticeMapper;
import org.example.timecoinweb.service.NoticeCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoticeCacheMetricsTests {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final NoticeMapper mapper = mock(NoticeMapper.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values = mock(ValueOperations.class);
    final NoticeCacheService cache = new NoticeCacheService(jdbc, mapper, redis, new ObjectMapper());
    NoticeCacheMetricsTests() {
        ReflectionTestUtils.setField(cache,"enabled",true);
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(1L);
        when(redis.opsForValue()).thenReturn(values);
        when(mapper.listRecent(anyInt())).thenReturn(List.of());
    }
    @Test void warmHitAndColdRebuildHaveDifferentCounters() {
        when(values.get("timebank:notices:v1")).thenReturn(null,null,"[]");
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        assertTrue(cache.recent(10).isEmpty());
        assertTrue(cache.recent(10).isEmpty());
        assertEquals(2L,cache.metrics().get("requests"));
        assertEquals(1L,cache.metrics().get("hits"));
        assertEquals(1L,cache.metrics().get("misses"));
        assertEquals(1L,cache.metrics().get("rebuilds"));
        verify(mapper,times(1)).listRecent(100);
    }
    @Test void lockContentionIsNotCountedAsHitOrRebuild() {
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);
        assertThrows(ResponseStatusException.class,()->cache.recent(10));
        assertEquals(1L,cache.metrics().get("lockBusy"));
        assertEquals(0L,cache.metrics().get("hits"));
        verifyNoInteractions(mapper);
    }
    @Test void redisFailureCountsFallbackWithoutInventingCacheHit() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("test disconnect"));
        assertTrue(cache.recent(10).isEmpty());
        assertEquals(1L,cache.metrics().get("fallbacks"));
        assertEquals(0L,cache.metrics().get("hits"));
        verify(mapper).listRecent(10);
    }
}
