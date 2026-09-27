package com.george.distributed_rate_limiter;

import com.george.distributed_rate_limiter.core.NonLuaTokenBucketLimiter;
import com.george.distributed_rate_limiter.core.RateLimiter;
import com.george.distributed_rate_limiter.core.TokenBucketLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.FileUrlResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Testcontainers
public class RateLimiterConcurrencyTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7"))
                    .withExposedPorts(6379);

    static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        redis.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();

        redisTemplate.opsForValue().set("warmup", "ok");
    }

    @AfterAll
    static void stopRedis() {
        redis.stop();
    }

    @BeforeEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    public void luaVersion_neverExceedsCapacityUnderConcurrency() throws Exception {
        int capacity = 10;
        TokenBucketLimiter limiter = new TokenBucketLimiter(redisTemplate, capacity, 0.0001);

        int allowed = fireConcurrency(limiter,"lua_test_user", 50);
        System.out.println("********************************");
        System.out.printf("lua version allowed %d request, and the capacity was %d%n",
                allowed,
                capacity);

        assertEquals(capacity,allowed, "allow exact as the capacity under concurrency!");
    }

    @Test
    public void nonluaVersion_exceedsCapacityUnderConcurrency() throws Exception {
        int capacity = 10;
        NonLuaTokenBucketLimiter limiter = new NonLuaTokenBucketLimiter(redisTemplate, capacity, 0.0001);
        int allowed = fireConcurrency(limiter,"nonlua_test_user", 50);
        System.out.println("********************************");
        System.out.printf("non-lua allowed %d request, and the capacity was %d%n",
                allowed,
                capacity);

        assertTrue(allowed > capacity,
                "over allowed under concurrency");
    }

    private int fireConcurrency(RateLimiter limiter, String key, int threadCount) throws Exception {
        //create a thread pool with the specified number of threads
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        //count how many tokens in total taken
        AtomicInteger allowedCount = new AtomicInteger(0);
        //store the handle of threads
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                try {
                    //wait all ready and start at the same time.
                    startLatch.await();
                    if (limiter.tryAcquire(key)) {
                        allowedCount.incrementAndGet();
                    }
                }catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                }

            }));

        }
        //wait till all thread created and ready.
        readyLatch.await();
        //spike
        startLatch.countDown();
        //block main thread to make sure eveyr thread executed completely.
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        //return total token taken
        return allowedCount.get();
    }

}
