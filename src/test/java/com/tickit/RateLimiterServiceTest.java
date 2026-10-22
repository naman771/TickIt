package com.tickit;

import com.tickit.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RateLimiterServiceTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String testKey = "rl:test:unit_user_999";

    @BeforeEach
    void setUp() {
        redisTemplate.delete(testKey);
    }

    @Test
    @DisplayName("Should allow requests within bucket capacity (20 tokens)")
    void shouldAllowRequestsWithinCapacity() {
        // App capacity is 20 tokens
        for (int i = 1; i <= 20; i++) {
            boolean allowed = rateLimiterService.isAllowed(testKey);
            assertTrue(allowed, "Request #" + i + " should be allowed within capacity");
        }
    }

    @Test
    @DisplayName("Should reject requests once bucket tokens are exhausted")
    void shouldRejectWhenCapacityExceeded() {
        // Exhaust all 20 tokens
        for (int i = 0; i < 20; i++) {
            rateLimiterService.isAllowed(testKey);
        }

        // 21st request should be rejected
        boolean rejected = rateLimiterService.isAllowed(testKey);
        assertFalse(rejected, "Request exceeding capacity must be blocked by rate limiter");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void shouldRefillTokensOverTime() throws InterruptedException {
        // Exhaust bucket
        for (int i = 0; i < 20; i++) {
            rateLimiterService.isAllowed(testKey);
        }
        assertFalse(rateLimiterService.isAllowed(testKey), "Bucket should be exhausted");

        // Wait 500ms (refill rate is 5 tokens/sec -> ~2.5 tokens added)
        Thread.sleep(500);

        assertTrue(rateLimiterService.isAllowed(testKey), "Tokens should have refilled after wait");
    }
}
