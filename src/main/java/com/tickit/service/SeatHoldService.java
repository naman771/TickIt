package com.tickit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Atomic holds and an expiry index; the database remains authoritative for bookings. */
@Service
@RequiredArgsConstructor
public class SeatHoldService {
    private final StringRedisTemplate redis;
    private static final String INDEX = "hold:expiry";
    private static final DefaultRedisScript<Long> ACQUIRE = script("acquire_hold.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("release_hold.lua");
    @Value("${app.seat-hold.ttl-seconds:120}")
    private int holdTtlSeconds;

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/" + name));
        script.setResultType(Long.class);
        return script;
    }
    private String holdKey(Long eventId, Long seatId) { return "hold:" + eventId + ":" + seatId; }
    public boolean acquireHold(Long eventId, Long seatId, Long userId) {
        return Long.valueOf(1).equals(redis.execute(ACQUIRE,
                List.of(holdKey(eventId, seatId), INDEX), userId.toString(), String.valueOf(holdTtlSeconds)));
    }
    public boolean isHoldValid(Long eventId, Long seatId, Long userId) {
        return userId.toString().equals(redis.opsForValue().get(holdKey(eventId, seatId)));
    }
    /** Internal administrative cleanup, also used by isolated test fixtures. */
    public void releaseHold(Long eventId, Long seatId) { release(eventId, seatId, "*"); }
    public boolean releaseHold(Long eventId, Long seatId, Long userId) {
        return release(eventId, seatId, userId.toString());
    }
    private boolean release(Long eventId, Long seatId, String owner) {
        return Long.valueOf(1).equals(redis.execute(RELEASE, List.of(holdKey(eventId, seatId), INDEX), owner));
    }
    public boolean isSeatHeld(Long eventId, Long seatId) {
        return Boolean.TRUE.equals(redis.hasKey(holdKey(eventId, seatId)));
    }
    public long remainingSeconds(Long eventId, Long seatId) {
        Long ttl = redis.getExpire(holdKey(eventId, seatId), TimeUnit.MILLISECONDS);
        return ttl == null ? 0 : Math.max(0, (ttl + 999) / 1000);
    }
    public Long getHoldingUser(Long eventId, Long seatId) {
        String value = redis.opsForValue().get(holdKey(eventId, seatId));
        return value == null ? null : Long.valueOf(value);
    }
}
