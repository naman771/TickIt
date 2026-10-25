package com.tickit.notification;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable notification intent. Transactional callers commit it alongside the business change. */
@Service
@RequiredArgsConstructor
public class NotificationOutbox {
  private final JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Value(
      "${app.public-url:https://tickit-mpy2.onrender.com}")
  private String publicUrl;

  private String ticketLink() {
    return "\nMy tickets: " + publicUrl.replaceAll("/$", "") + "/#tickets";
  }

  private void enqueue(
      String key,
      long user,
      long event,
      UUID order,
      Long change,
      String kind,
      String subject,
      String body) {
    // Recipient is sourced from our verified identity profile, never from a checkout payload.
    jdbc.update(
        "INSERT INTO"
            + " notification_outbox(id,dedupe_key,user_id,event_id,order_id,event_change_id,kind,recipient,subject,body)"
            + " SELECT ?,?,?,?,?,?,?,email,?,? FROM app_user WHERE id=? ON CONFLICT(dedupe_key) DO"
            + " NOTHING",
        UUID.randomUUID(),
        key,
        user,
        event,
        order,
        change,
        kind,
        subject.substring(0, Math.min(subject.length(), 240)),
        body,
        user);
  }

  public void booking(UUID order) {
    booking(order, "booking:" + order);
  }

  public void resend(UUID order, UUID request) {
    booking(order, "resend:" + request);
  }

  private void booking(UUID order, String dedupe) {
    var rows =
        jdbc.queryForList(
            "SELECT o.user_id,o.event_id,e.name FROM checkout_order o JOIN event e ON"
                + " e.id=o.event_id WHERE o.id=? AND o.status='CONFIRMED' AND o.provider_payment_id"
                + " IS NOT NULL",
            order);
    for (var row : rows)
      enqueue(
          dedupe,
          ((Number) row.get("user_id")).longValue(),
          ((Number) row.get("event_id")).longValue(),
          order,
          null,
          "BOOKING",
          "Your TickIt booking is confirmed",
          "Your booking for "
              + row.get("name")
              + " is confirmed.\nOrder reference: "
              + order
              + "\n"
              + "Open My tickets on the TickIt website to view and download each admission"
              + " ticket.\n"
              + "Keep your QR tickets private. Each admission can be checked in once."
              + ticketLink());
  }

  public void eventChange(long change) {
    var rows =
        jdbc.queryForList(
            "SELECT DISTINCT o.user_id,o.event_id,e.name,c.action,c.reason,"
                + " c.new_start,c.new_timezone FROM event_change c JOIN event e ON e.id=c.event_id"
                + " JOIN checkout_order o ON o.event_id=e.id AND o.status='CONFIRMED' WHERE c.id=?",
            change);
    for (var row : rows) {
      long user = ((Number) row.get("user_id")).longValue(),
          event = ((Number) row.get("event_id")).longValue();
      String action = (String) row.get("action");
      enqueue(
          "change:" + change + ":" + user,
          user,
          event,
          null,
          change,
          "EVENT_CHANGE",
          "Update to your TickIt event",
          row.get("name")
              + " has been "
              + ("RESCHEDULED".equals(action) ? "rescheduled" : "cancelled")
              + ".\n"
              + row.get("reason")
              + ("RESCHEDULED".equals(action)
                  ? "\nNew start: "
                      + row.get("new_start")
                      + " ("
                      + row.get("new_timezone")
                      + "). Your confirmed tickets remain on your account."
                  : "\n"
                      + "Your tickets cannot be used for entry. Any eligible full refund is"
                      + " tracked in My tickets.")
              + "\nCheck My tickets for the latest event and refund status."
              + ticketLink());
    }
  }

  public void refund(UUID order) {
    var rows =
        jdbc.queryForList(
            "SELECT o.user_id,o.event_id,e.name,r.amount,r.currency,r.status FROM refund_job r JOIN"
                + " checkout_order o ON o.id=r.order_id JOIN event e ON e.id=o.event_id WHERE"
                + " o.id=? AND r.status IN ('PROCESSED','FAILED','REVIEW')",
            order);
    for (var row : rows) {
      String status = (String) row.get("status");
      enqueue(
          "refund:" + order + ":" + status,
          ((Number) row.get("user_id")).longValue(),
          ((Number) row.get("event_id")).longValue(),
          order,
          null,
          "REFUND",
          "Your TickIt refund update",
          "Refund for "
              + row.get("name")
              + ".\nOrder reference: "
              + order
              + "\nStatus: "
              + status
              + ("PROCESSED".equals(status)
                  ? "\n"
                      + "The payment provider has processed your full refund. Bank credit timing"
                      + " depends on the payment method."
                  : "\n"
                      + "This refund requires support follow-up. Keep your order reference and"
                      + " check My tickets. Do not pay again.")
              + ticketLink());
    }
  }

  /** Recheck durable state immediately before attempting an external send. */
  public boolean current(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT CASE n.kind WHEN 'BOOKING' THEN EXISTS(SELECT 1 FROM checkout_order o JOIN"
                + " event e ON e.id=o.event_id WHERE o.id=n.order_id AND o.status='CONFIRMED' AND"
                + " o.provider_payment_id IS NOT NULL AND e.publication_status='PUBLISHED' AND NOT"
                + " EXISTS(SELECT 1 FROM refund_job r WHERE r.order_id=o.id)) WHEN 'EVENT_CHANGE'"
                + " THEN n.event_change_id=(SELECT max(c.id) FROM event_change c WHERE"
                + " c.event_id=n.event_id) WHEN 'REFUND' THEN EXISTS(SELECT 1 FROM refund_job r"
                + " WHERE r.order_id=n.order_id AND"
                + " n.dedupe_key='refund:'||r.order_id::text||':'||r.status) ELSE false END FROM"
                + " notification_outbox n WHERE n.id=?",
            Boolean.class,
            id));
  }

  /**
   * Bounded repair sweep; recovers missed post-payment / refund intents without duplicating jobs.
   */
  public void recover() {
    for (var id :
        jdbc.queryForList(
            "SELECT o.id FROM checkout_order o WHERE o.status='CONFIRMED' AND o.provider_payment_id"
                + " IS NOT NULL AND NOT EXISTS(SELECT 1 FROM notification_outbox n WHERE"
                + " n.dedupe_key='booking:'||o.id::text) ORDER BY o.created_at LIMIT 50",
            UUID.class)) booking(id);
    for (var id :
        jdbc.queryForList(
            "SELECT r.order_id FROM refund_job r WHERE r.status IN ('PROCESSED','FAILED','REVIEW')"
                + " AND NOT EXISTS(SELECT 1 FROM notification_outbox n WHERE"
                + " n.dedupe_key='refund:'||r.order_id::text||':'||r.status) ORDER BY r.created_at"
                + " LIMIT 50",
            UUID.class)) refund(id);
  }
}
