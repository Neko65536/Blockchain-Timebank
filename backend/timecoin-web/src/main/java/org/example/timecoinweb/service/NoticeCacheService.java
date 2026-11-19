package org.example.timecoinweb.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.pojo.Notice;
import org.example.timecoinweb.mapper.NoticeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/** 公告允许短暂旧读；支付、名额和权限始终以数据库为准。版本随公告事务提交，旧读不能覆盖新版本缓存。 */
@Service
@RequiredArgsConstructor
public class NoticeCacheService {
    private final JdbcTemplate jdbc;
    private final NoticeMapper notices;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    @Value("${app.redis-enabled:false}") private boolean enabled;
    private final LongAdder requests = new LongAdder(), hits = new LongAdder(), misses = new LongAdder(),
            rebuilds = new LongAdder(), lockBusy = new LongAdder(), fallbacks = new LongAdder();

    /** 单进程累计值；用压测前后差值计算，重启归零，不混入Redis锁或其他业务的GET。 */
    public Map<String, Long> metrics() {
        return Map.of("requests", requests.sum(), "hits", hits.sum(), "misses", misses.sum(),
                "rebuilds", rebuilds.sum(), "lockBusy", lockBusy.sum(), "fallbacks", fallbacks.sum());
    }
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);

    public List<Notice> recent(int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        if (!enabled) return notices.listRecent(n);
        requests.increment();
        long version = jdbc.queryForObject("select version_value from cache_revision where cache_name='notices'", Long.class);
        String key = "timebank:notices:v" + version;
        String token = UUID.randomUUID().toString();
        String lock = key + ":lock";
        try {
            String saved = redis.opsForValue().get(key);
            if (saved != null) { List<Notice> result = slice(decode(saved), n); hits.increment(); return result; }
            misses.increment();
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, Duration.ofSeconds(3)))) {
                lockBusy.increment();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "公告正在刷新，请稍后重试");
            }
            try {
                saved = redis.opsForValue().get(key);
                if (saved != null) return slice(decode(saved), n);
                List<Notice> rows = notices.listRecent(100);
                redis.opsForValue().set(key, encode(rows), Duration.ofSeconds(rows.isEmpty() ? 5 : 30 + ThreadLocalRandom.current().nextInt(10)));
                rebuilds.increment();
                return slice(rows, n);
            } finally { redis.execute(UNLOCK, Collections.singletonList(lock), token); }
        } catch (DataAccessException redisUnavailable) {
            fallbacks.increment();
            // 公告读取选择可用性；写入限流则明确失败关闭，不共用降级策略。
            return notices.listRecent(n);
        }
    }
    private List<Notice> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<Notice>>() { }); }
        catch (Exception e) { throw new IllegalStateException("公告缓存格式错误", e); }
    }
    private String encode(List<Notice> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("公告无法序列化", e); }
    }
    private List<Notice> slice(List<Notice> values, int n) { return new ArrayList<>(values.subList(0, Math.min(n, values.size()))); }
    public void advanceVersion() {
        if (jdbc.update("update cache_revision set version_value=version_value+1 where cache_name='notices'") != 1)
            throw new IllegalStateException("公告缓存版本记录缺失");
    }
}
