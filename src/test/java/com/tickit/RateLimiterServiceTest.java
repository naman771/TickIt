package com.tickit;

import static org.junit.jupiter.api.Assertions.*;

import com.tickit.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = "app.rate-limit.capacity=20")
class RateLimiterServiceTest {

  @Autowired private RateLimiterService rateLimiterService;

  @Autowired private StringRedisTemplate redisTemplate;

  private final String testKey = "rl:test:" + java.util.UUID.randomUUID();

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

  @org.junit.jupiter.api.AfterEach
  void cleanup() {
    redisTemplate.delete(testKey);
  }

  @Test
  void twoServiceInstancesShareOneAtomicBudgetUnderContention() throws Exception {
    var second = new RateLimiterService(redisTemplate);
    org.springframework.test.util.ReflectionTestUtils.setField(second, "capacity", 20);
    org.springframework.test.util.ReflectionTestUtils.setField(second, "refillRate", 5);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(second, "init");
    // Freeze refill by seeding a future Redis timestamp. All 64 requests compete for 20 tokens.
    seed(20, 60000);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 64; i++) {
        var instance = i % 2 == 0 ? rateLimiterService : second;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return instance.isAllowed(testKey);
                }));
      }
      start.countDown();
      int allowed = 0;
      for (var result : results)
        if (result.get(15, java.util.concurrent.TimeUnit.SECONDS)) allowed++;
      assertEquals(20, allowed);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void slowRefillCannotExpireIntoAnEarlyFreeBurst() {
    seed(0, 0);
    var script =
        new org.springframework.data.redis.core.script.DefaultRedisScript<java.util.List>();
    script.setLocation(
        new org.springframework.core.io.ClassPathResource("scripts/rate_limiter.lua"));
    script.setResultType(java.util.List.class);
    var result = redisTemplate.execute(script, java.util.List.of(testKey), "1000", "1", "details");
    assertEquals(0L, result.get(0));
    assertTrue(
        redisTemplate.getExpire(testKey, java.util.concurrent.TimeUnit.MILLISECONDS) > 990000);
    assertTrue((Long) result.get(2) > 0);
  }

  @Test
  void backwardsClockDoesNotInventTokensAndRetryIncludesTheDelay() {
    seed(0, 60000);
    var decision = rateLimiterService.check(testKey);
    assertFalse(decision.allowed());
    assertTrue(decision.retryAfterSeconds() >= 59);
    assertEquals(0, decision.remaining());
  }

  @Test
  void missingRedisResultIsInfrastructureFailure() {
    var unavailable = new RateLimiterService(org.mockito.Mockito.mock(StringRedisTemplate.class));
    org.springframework.test.util.ReflectionTestUtils.setField(unavailable, "capacity", 20);
    org.springframework.test.util.ReflectionTestUtils.setField(unavailable, "refillRate", 5);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(unavailable, "init");
    assertThrows(
        org.springframework.dao.DataAccessResourceFailureException.class,
        () -> unavailable.check(testKey));
  }

  private void seed(int tokens, int futureMillis) {
    var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
    script.setScriptText(
        "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); "
            + "redis.call('HSET',KEYS[1],'tokens',ARGV[1],'last',n+tonumber(ARGV[2])); return 1");
    script.setResultType(Long.class);
    redisTemplate.execute(
        script, java.util.List.of(testKey), String.valueOf(tokens), String.valueOf(futureMillis));
  }
}
