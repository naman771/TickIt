package com.tickit.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CheckoutStore {
  private final JdbcTemplate jdbc;

  public record Order(
      UUID id,
      long userId,
      long eventId,
      String fingerprint,
      String status,
      long amount,
      String currency,
      String providerOrderId,
      String paymentId,
      UUID admissionHoldId,
      Instant expiresAt) {}

  public record Item(Long seatId, Long ticketTypeId, String label, int quantity, long unitAmount) {}

  private Order map(ResultSet r, int row) throws SQLException {
    return new Order(
        r.getObject("id", UUID.class),
        r.getLong("user_id"),
        r.getLong("event_id"),
        r.getString("request_fingerprint"),
        r.getString("status"),
        r.getLong("amount"),
        r.getString("currency"),
        r.getString("provider_order_id"),
        r.getString("provider_payment_id"),
        r.getObject("admission_hold_id", UUID.class),
        r.getTimestamp("expires_at").toInstant());
  }

  public Order get(UUID id) {
    return jdbc.query("SELECT * FROM checkout_order WHERE id=?", this::map, id).stream()
        .findFirst()
        .orElse(null);
  }

  public Order forKey(long user, UUID key) {
    return jdbc
        .query(
            "SELECT * FROM checkout_order WHERE user_id=? AND idempotency_key=?",
            this::map,
            user,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Order forProvider(String id) {
    return jdbc
        .query("SELECT * FROM checkout_order WHERE provider_order_id=?", this::map, id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Item> items(UUID id) {
    return jdbc.query(
        "SELECT * FROM checkout_item WHERE order_id=? ORDER BY id",
        (r, n) ->
            new Item(
                r.getObject("seat_id", Long.class),
                r.getObject("ticket_type_id", Long.class),
                r.getString("label"),
                r.getInt("quantity"),
                r.getLong("unit_amount")),
        id);
  }

  public Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
  }

  public void status(UUID id, String status, String payment) {
    jdbc.update(
        "UPDATE checkout_order SET status=?, provider_payment_id=COALESCE(?,provider_payment_id),"
            + " confirmed_at=CASE WHEN ?='CONFIRMED' THEN clock_timestamp() ELSE confirmed_at END"
            + " WHERE id=?",
        status,
        payment,
        status,
        id);
  }

  public List<Order> history(long user) {
    return jdbc.query(
        "SELECT * FROM checkout_order WHERE user_id=? ORDER BY last_checked_at ASC NULLS FIRST,"
            + " created_at LIMIT 100",
        this::map,
        user);
  }

  public List<Order> reconcilable() {
    return jdbc.query(
        "SELECT * FROM checkout_order WHERE provider_order_id IS NOT NULL AND status IN"
            + " ('PENDING','EXPIRED') AND created_at>clock_timestamp()-interval '1 day' ORDER BY"
            + " last_checked_at ASC NULLS FIRST, created_at LIMIT 10",
        this::map);
  }
}
