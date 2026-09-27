package com.george.distributed_rate_limiter.core;

public interface RateLimiter {

    /**
     *
     */
    boolean tryAcquire(String key);
}
