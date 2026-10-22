package com.tickit.controller;

import com.tickit.config.AuthSettings;
import com.tickit.service.DeveloperAccess;
import com.tickit.service.AssetStorageService;
import com.tickit.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/developer")
@RequiredArgsConstructor
public class DeveloperController {
    private final AuthSettings settings;
    private final DeveloperAccess access;
    private final AssetStorageService storage;
    private final EventRepository events;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final DataSource database;
    private final StringRedisTemplate redis;
    @Value("${app.email.enabled:false}") private boolean emailEnabled;

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        access.requireDeveloper();
        boolean databaseReady = false, redisReady = false;
        try (var connection = database.getConnection()) { databaseReady = connection.isValid(2); }
        catch (Exception ignored) { }
        try { redisReady = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping())); }
        catch (Exception ignored) { }
        return Map.of("checkedAt", Instant.now().toString(),
                "services", Map.of("postgresql", databaseReady, "redis", redisReady, "objectStorage", storage.healthy()),
                "integrations", Map.of("authentication", settings.isClerk(), "payments", false, "email", emailEnabled),
                "catalog", Map.of("events", events.count(), "seats", seats.count(),
                        "bookings", bookings.count(), "accounts", users.count()));
    }
}
