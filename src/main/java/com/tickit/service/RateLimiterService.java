package com.tickit.service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Shared token buckets: Redis atomically refills/checks/consumes each bucket using its clock. */
@Service
public class RateLimiterService {
  private final StringRedisTemplate redis;

  @Value("${app.rate-limit.capacity:20}")
  private int capacity;

  @Value("${app.rate-limit.refill-rate:5}")
  private int refillRate;

  private DefaultRedisScript<List> rateLimiterScript;

  public RateLimiterService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @PostConstruct
  void init() {
    if (capacity < 1 || refillRate < 1) {
      throw new IllegalArgumentException("Rate-limit capacity and refill rate must be positive");
    }
    rateLimiterScript = new DefaultRedisScript<>();
    rateLimiterScript.setLocation(new ClassPathResource("scripts/rate_limiter.lua"));
    rateLimiterScript.setResultType(List.class);
  }

  public record Decision(
      boolean allowed, int limit, long remaining, long retryAfterSeconds, long resetEpochSeconds) {
    public Decision restrict(Decision other) {
      return new Decision(
          allowed && other.allowed,
          Math.min(limit, other.limit),
          Math.min(remaining, other.remaining),
          Math.max(retryAfterSeconds, other.retryAfterSeconds),
          Math.max(resetEpochSeconds, other.resetEpochSeconds));
    }
  }

  public Decision check(String bucketKey) {
    List<?> result =
        redis.execute(
            rateLimiterScript,
            List.of(bucketKey),
            String.valueOf(capacity),
            String.valueOf(refillRate),
            "details");
    if (result == null
        || result.size() != 4
        || result.stream().anyMatch(v -> !(v instanceof Long))) {
      throw new DataAccessResourceFailureException("Rate limiter is temporarily unavailable");
    }
    return new Decision(
        (Long) result.get(0) == 1L,
        capacity,
        (Long) result.get(1),
        ((Long) result.get(2) + 999) / 1000,
        (Long) result.get(3));
  }

  public boolean isAllowed(String bucketKey) {
    return check(bucketKey).allowed();
  }

  public boolean isAllowedForUser(Long userId) {
    return isAllowed("rl:user:" + userId);
  }

  public boolean isAllowedForIp(String ipAddress) {
    return isAllowed("rl:ip:" + ipAddress);
  }
}
