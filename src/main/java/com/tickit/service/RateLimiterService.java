package com.tickit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collections;

/**
 * Token-bucket rate limiter backed by an atomic Redis Lua script.
 * Supports per-user and per-IP buckets.
 */
@Service
@Slf4j
public class RateLimiterService {

    private final StringRedisTemplate redis;

    @Value("${app.rate-limit.capacity:20}")
    private int capacity;

    @Value("${app.rate-limit.refill-rate:5}")
    private int refillRate;

    private DefaultRedisScript<Long> rateLimiterScript;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void init() {
        rateLimiterScript = new DefaultRedisScript<>();
        rateLimiterScript.setLocation(new ClassPathResource("scripts/rate_limiter.lua"));
        rateLimiterScript.setResultType(Long.class);
    }

    /**
     * Check whether a request from the given key is allowed.
     * @param bucketKey e.g. "rl:user:42" or "rl:ip:192.168.1.1"
     * @return true if allowed, false if rate-limited
     */
    public boolean isAllowed(String bucketKey) {
        long now = System.currentTimeMillis();
        Long result = redis.execute(
                rateLimiterScript,
                Collections.singletonList(bucketKey),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now)
        );
        boolean allowed = result != null && result == 1L;
        if (!allowed) {
            log.warn("Rate limit hit for key={}", bucketKey);
        }
        return allowed;
    }

    /** Convenience: check by user ID. */
    public boolean isAllowedForUser(Long userId) {
        return isAllowed("rl:user:" + userId);
    }

    /** Convenience: check by IP address. */
    public boolean isAllowedForIp(String ipAddress) {
        return isAllowed("rl:ip:" + ipAddress);
    }
}
