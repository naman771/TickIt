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
        "SELECT"
            + " o.id,o.event_id,o.status,o.amount,o.currency,o.provider_order_id,o.provider_payment_id,o.created_at,e.publication_status"
            + " AS \"eventStatus\",r.status AS \"refundStatus\",(o.status='CONFIRMED' AND"
            + " e.publication_status IN ('CANCELLED','DELETED')) AS \"refundRequired\" FROM"
            + " checkout_order o JOIN event e ON e.id=o.event_id LEFT JOIN refund_job r ON"
            + " r.order_id=o.id WHERE (r.status IS NULL OR r.status<>'PROCESSED') AND (o.status IN"
            + " ('REVIEW_REQUIRED','CREATE_UNKNOWN') OR (o.status='CONFIRMED' AND"
            + " e.publication_status IN ('CANCELLED','DELETED'))) ORDER BY o.created_at LIMIT 100");
  }

  @GetMapping("/payments/refunds")
  public java.util.List<Map<String, Object>> refunds() {
    access.requireDeveloper();
    return jdbc.queryForList(
        "SELECT"
            + " r.order_id,r.status,r.amount,r.currency,r.reason,r.provider_refund_id,r.failures,r.last_error,r.created_at,r.updated_at,o.event_id"
            + " FROM refund_job r JOIN checkout_order o ON o.id=r.order_id ORDER BY r.created_at"
            + " DESC LIMIT 100");
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
