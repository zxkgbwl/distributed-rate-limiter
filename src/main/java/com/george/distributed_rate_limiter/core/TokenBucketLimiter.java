package com.george.distributed_rate_limiter.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class TokenBucketLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final int capacity;
    private final double refillRate;
    @Autowired
    public TokenBucketLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${ratelimiter.capacity}") int capacity,
            @Value("${ratelimiter.refill-rate}") double refillRate) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public boolean tryAcquire(String key) {
        Long allowed = redisTemplate.execute(
                script,
                Collections.singletonList("rate_limiter:" + key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                "1"
        );
        return allowed != null && allowed == 1L;
    }
}