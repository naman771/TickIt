package com.tickit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Manages short-TTL seat holds in Redis to prevent two users
 * from booking the same seat simultaneously.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    private final StringRedisTemplate redis;

    @Value("${app.seat-hold.ttl-seconds:120}")
    private int holdTtlSeconds;

    private String holdKey(Long eventId, Long seatId) {
        return "hold:" + eventId + ":" + seatId;
    }

    /**
     * Try to acquire a hold on a seat for a given user.
     * @return true if the hold was successfully acquired
     */
    public boolean acquireHold(Long eventId, Long seatId, Long userId) {
        String key = holdKey(eventId, seatId);
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(key, userId.toString(), Duration.ofSeconds(holdTtlSeconds));
        if (Boolean.TRUE.equals(acquired)) {
            log.info("Hold acquired: event={}, seat={}, user={}", eventId, seatId, userId);
            return true;
        }
        // Check if the same user already holds it (idempotent)
        String existing = redis.opsForValue().get(key);
        if (userId.toString().equals(existing)) {
            log.info("Hold already owned by same user: event={}, seat={}, user={}", eventId, seatId, userId);
            return true;
        }
        log.info("Hold denied (held by another): event={}, seat={}, user={}", eventId, seatId, userId);
        return false;
    }

    /**
     * Check if a valid hold exists for this user+seat.
     */
    public boolean isHoldValid(Long eventId, Long seatId, Long userId) {
        String key = holdKey(eventId, seatId);
        String holder = redis.opsForValue().get(key);
        return userId.toString().equals(holder);
    }

    /**
     * Release a hold (after successful booking or cancellation).
     */
    public void releaseHold(Long eventId, Long seatId) {
        String key = holdKey(eventId, seatId);
        redis.delete(key);
        log.info("Hold released: event={}, seat={}", eventId, seatId);
    }

    /**
     * Check whether a seat is currently held by anyone.
     */
    public boolean isSeatHeld(Long eventId, Long seatId) {
        return Boolean.TRUE.equals(redis.hasKey(holdKey(eventId, seatId)));
    }

    /**
     * Get the userId currently holding a seat (or null).
     */
    public Long getHoldingUser(Long eventId, Long seatId) {
        String val = redis.opsForValue().get(holdKey(eventId, seatId));
        return val != null ? Long.parseLong(val) : null;
    }

    /**
     * Find all hold keys for a given event (for cleanup/monitoring).
     */
    public Set<String> findHoldKeys(Long eventId) {
        return redis.keys("hold:" + eventId + ":*");
    }
}
