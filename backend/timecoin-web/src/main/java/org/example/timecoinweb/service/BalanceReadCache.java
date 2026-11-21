package org.example.timecoinweb.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * J8：链上余额读的短 TTL 缓存，缓解「每次余额查询都实时 RPC」的热点压力。
 *
 * 正确性取舍：TTL 默认 3 秒且写操作主动失效，缓存仅用于读多写少的查询展示；
 * 结算/转账/发币的最终状态以链上和 settlement_order 为准，不依赖本缓存。
 * 缓存 miss 时才触发 RPC，命中不产生链上调用。
 */
@Component
public class BalanceReadCache {
    private final long ttlSeconds;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public BalanceReadCache(@Value("${app.balance-cache-ttl-seconds:3}") long ttlSeconds) {
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    public BigInteger get(String userId, java.util.function.Supplier<BigInteger> loader) {
        if (ttlSeconds <= 0) return loader.get(); // 关闭缓存时直读
        long now = Instant.now().getEpochSecond();
        Entry cached = cache.get(userId);
        if (cached != null && cached.expiresAt > now) return cached.value;
        BigInteger value = loader.get();
        if (value != null) cache.put(userId, new Entry(value, now + ttlSeconds));
        return value;
    }

    /** 写操作后主动失效，避免 TTL 内的陈旧展示。 */
    public void invalidate(String userId) {
        if (userId != null) cache.remove(userId);
    }

    public void clear() { cache.clear(); }

    private record Entry(BigInteger value, long expiresAt) {}
}
