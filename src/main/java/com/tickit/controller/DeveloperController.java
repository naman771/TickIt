package com.tickit.controller;

import com.tickit.config.AuthSettings;
import com.tickit.repository.*;
import com.tickit.service.AssetStorageService;
import com.tickit.service.DeveloperAccess;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/developer")
@RequiredArgsConstructor
public class DeveloperController {
  private final AuthSettings settings;
  private final com.tickit.payment.PaymentSettings payments;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final DeveloperAccess access;
  private final AssetStorageService storage;
  private final EventRepository events;
  private final SeatRepository seats;
  private final BookingRepository bookings;
  private final UserRepository users;
  private final DataSource database;
  private final StringRedisTemplate redis;

  @Value("${app.email.enabled:false}")
  private boolean emailEnabled;

  @GetMapping("/payments/review")
  public java.util.List<Map<String, Object>> paymentReview() {
    access.requireDeveloper();
    return jdbc.queryForList(
        "SELECT id,event_id,status,amount,currency,provider_order_id,provider_payment_id,created_at"
            + " FROM checkout_order WHERE status IN ('REVIEW_REQUIRED','CREATE_UNKNOWN') ORDER BY"
            + " created_at LIMIT 100");
  }

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    access.requireDeveloper();
    boolean databaseReady = false, redisReady = false;
    try (var connection = database.getConnection()) {
      databaseReady = connection.isValid(2);
    } catch (Exception ignored) {
    }
    try {
      redisReady =
          "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
    } catch (Exception ignored) {
    }
    return Map.of(
        "checkedAt",
        Instant.now().toString(),
        "services",
        Map.of(
            "postgresql", databaseReady, "redis", redisReady, "objectStorage", storage.healthy()),
        "integrations",
        Map.of(
            "authentication",
            settings.isClerk(),
            "payments",
            payments.isEnabled(),
            "email",
            emailEnabled),
        "catalog",
        Map.of(
            "events",
            events.count(),
            "seats",
            seats.count(),
            "bookings",
            bookings.count(),
            "accounts",
            users.count()));
  }
}
