-- 返回0表示放行，正数表示需要等待的毫秒数；仅记录已放行请求。
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= limit then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return math.max(1, tonumber(oldest[2]) + window - now)
end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
return 0
