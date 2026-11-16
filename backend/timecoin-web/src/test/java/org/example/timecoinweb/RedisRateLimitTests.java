package org.example.timecoinweb;

import org.example.timecoinweb.interceptor.RedisRateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisRateLimitTests {
    @Test
    void asyncRedispatchDoesNotChargeAgain() throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        RedisRateLimitInterceptor limiter=new RedisRateLimitInterceptor(redis);
        ReflectionTestUtils.setField(limiter,"enabled",true);
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/users/old/ai/chat");
        request.setAttribute("authenticatedUserId",2);request.setDispatcherType(javax.servlet.DispatcherType.ASYNC);
        assertTrue(limiter.preHandle(request,new MockHttpServletResponse(),new Object()));verifyNoInteractions(redis);
    }
    @Test
    void redisFailureRejectsAuthenticatedWriteWithoutExecutingBusiness() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("offline")).when(redis).execute(any(DefaultRedisScript.class), anyList(), any());
        RedisRateLimitInterceptor limiter = new RedisRateLimitInterceptor(redis);
        ReflectionTestUtils.setField(limiter, "enabled", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/info/coin/transfer");
        request.setAttribute("authenticatedUserId", 2);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request, response, new Object()));
        assertEquals(503, response.getStatus());
        assertEquals("3", response.getHeader("Retry-After"));
    }

    @Test
    void readAndPreflightDoNotConsumeWriteQuota() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRateLimitInterceptor limiter = new RedisRateLimitInterceptor(redis);
        ReflectionTestUtils.setField(limiter, "enabled", true);
        for (String method : new String[]{"GET", "OPTIONS"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/info/settlements");
            request.setAttribute("authenticatedUserId", 2);
            assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), new Object()));
        }
        verifyNoInteractions(redis);
    }
}
