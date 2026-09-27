package com.george.distributed_rate_limiter.demo;

import com.george.distributed_rate_limiter.core.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {
    private final RateLimiter rateLimiter;

    public DemoController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/ping")
    public ResponseEntity<String> ping(@RequestParam (defaultValue = "default-user") String userId) {
        if (rateLimiter.tryAcquire(userId)) {
            return ResponseEntity.ok("pong");
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("rate limit exceeded");
    }
}
