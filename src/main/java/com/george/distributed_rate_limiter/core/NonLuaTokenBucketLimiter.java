package com.george.distributed_rate_limiter.core;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

public class NonLuaTokenBucketLimiter implements RateLimiter{

    private final StringRedisTemplate redisTemplate;
    private final int capacity;
    private final double refillRate;

    public NonLuaTokenBucketLimiter(StringRedisTemplate redisTemplate, int capacity, double refillRate) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    @Override
    public boolean tryAcquire(String key) {
        String redisKey = "non_lua_rate_limiter:" + key;
        Long now = System.currentTimeMillis()/1000;

        List<Object> bucket = redisTemplate.opsForHash()
                .multiGet(redisKey, List.of("tokens", "last_refreshed"));

        double tokens;
        Long lastRefreshed;

        if (bucket.get(0) == null) {
            tokens = capacity;
            lastRefreshed = now;
        }
        else {
            tokens = Double.parseDouble((String) bucket.get(0));
            lastRefreshed = Long.parseLong((String) bucket.get(1));
        }
        Long elapsed = Math.max(0, now-lastRefreshed);
        tokens = Math.min(capacity, tokens + elapsed *refillRate);

        boolean allowed = tokens >= 1;
        if (allowed) {
            tokens -= 1;
        }

        redisTemplate.opsForHash().put(redisKey, "tokens",String.valueOf(tokens));
        redisTemplate.opsForHash().put(redisKey,"last_refreshed", String.valueOf(now));

        return allowed;
    }
}
