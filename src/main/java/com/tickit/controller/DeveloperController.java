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
  private final com.tickit.payment.RefundService refundService;
  private final com.tickit.notification.EmailSettings email;
  private final com.tickit.notification.NotificationReview notificationReview;
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

  public record RefundRetry(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
          String reason) {}

  @PostMapping("/payments/refunds/{id}/inspect")
  public Map<String, Object> inspectRefund(@PathVariable java.util.UUID id) {
    access.requireDeveloper();
    return refundService.inspect(id);
  }

  @PostMapping("/payments/refunds/{id}/retry")
  public com.tickit.payment.RefundService.Summary retryRefund(
      @PathVariable java.util.UUID id, @jakarta.validation.Valid @RequestBody RefundRetry input) {
    access.requireDeveloper();
    refundService.retry(
        id,
        input.reason(),
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
            .getName());
    return refundService.summary(id);
  }

  @GetMapping("/readiness")
  public Map<String, Object> readiness() {
    access.requireDeveloper();
    return Map.of(
        "checkedAt",
        Instant.now().toString(),
        "authentication",
        settings.isClerk()
            ? (settings.getIssuer().contains(".clerk.accounts.dev")
                ? "DEVELOPMENT_INSTANCE"
                : "CONFIGURED_REQUIRES_ACCEPTANCE")
            : "NOT_CONNECTED",
        "payments",
        payments.isEnabled() ? "TEST_ONLY" : "DISABLED",
        "email",
        Map.of("enabled", email.isEnabled(), "transport", email.getTransport()),
        "refundsRequiringAttention",
        jdbc.queryForObject(
            "SELECT count(*) FROM refund_job WHERE status IN ('FAILED','REVIEW')", Long.class),
        "failedNotifications",
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE status='FAILED'", Long.class),
        "publicLaunchApproved",
        false);
  }

  @GetMapping("/notifications")
  public Map<String, Object> notifications() {
    access.requireDeveloper();
    return Map.of(
        "deliveryEnabled",
        emailEnabled,
        "restrictedRecipients",
        email.recipients().size(),
        "counts",
        jdbc.queryForList("SELECT status,count(*) total FROM notification_outbox GROUP BY status"),
        "recent",
        jdbc.queryForList(
            "SELECT id,event_id,kind,status,recipient,attempts,created_at,sent_at,last_error FROM"
                + " notification_outbox ORDER BY created_at DESC LIMIT 100"));
  }

  public record NotificationAction(
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "RETRY|SUPPRESS|RELEASE")
          String action,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
          String reason) {}

  @PostMapping("/notifications/{id}/review")
  public Map<String, Object> reviewNotification(
      @PathVariable java.util.UUID id,
      @jakarta.validation.Valid @RequestBody NotificationAction input) {
    access.requireDeveloper();
    notificationReview.act(
        id,
        input.action(),
        input.reason(),
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
            .getName());
    return Map.of("recorded", true);
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
