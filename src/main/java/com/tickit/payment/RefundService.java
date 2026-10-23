package com.tickit.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable, full refunds. No provider network call runs inside an event transaction. */
@Service
@RequiredArgsConstructor
public class RefundService {
  private final JdbcTemplate jdbc;
  private final RazorpayGateway gateway;
  private final PaymentSettings settings;

  record Job(
      UUID orderId,
      String paymentId,
      long amount,
      String currency,
      String providerId,
      UUID lease) {}

  public record Summary(
      String status, long amount, String currency, String providerRefundId, String reason) {}

  // Event/checkout state is the durable source, so a missed callback or crash cannot lose work.
  public void enqueueEligible(Long eventId) {
    jdbc.update(
        "INSERT INTO refund_job(order_id,payment_id,amount,currency,reason) SELECT"
            + " o.id,o.provider_payment_id,o.amount,o.currency,CASE WHEN o.status='REVIEW_REQUIRED'"
            + " THEN 'TICKETS_NOT_ISSUED' ELSE 'EVENT_CANCELLED' END FROM checkout_order o JOIN"
            + " event e ON e.id=o.event_id WHERE o.provider_payment_id IS NOT NULL AND"
            + " (o.status='REVIEW_REQUIRED' OR (o.status='CONFIRMED' AND e.publication_status IN"
            + " ('CANCELLED','DELETED'))) "
            + (eventId == null ? "" : "AND o.event_id=? ")
            + "AND NOT EXISTS(SELECT 1 FROM refund_job r WHERE r.order_id=o.id) ORDER BY"
            + " o.created_at LIMIT 100 ON CONFLICT DO NOTHING",
        eventId == null ? new Object[0] : new Object[] {eventId});
  }

  private Job map(ResultSet r, int n) throws SQLException {
    return new Job(
        r.getObject("order_id", UUID.class),
        r.getString("payment_id"),
        r.getLong("amount"),
        r.getString("currency"),
        r.getString("provider_refund_id"),
        r.getObject("lease_token", UUID.class));
  }

  public Summary summary(UUID orderId) {
    return jdbc
        .query(
            "SELECT * FROM refund_job WHERE order_id=?",
            (r, n) ->
                new Summary(
                    r.getString("status"),
                    r.getLong("amount"),
                    r.getString("currency"),
                    r.getString("provider_refund_id"),
                    r.getString("reason")),
            orderId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean processOne() {
    if (!settings.isEnabled()) return false;
    UUID lease = UUID.randomUUID();
    var jobs =
        jdbc.query(
            "UPDATE refund_job SET lease_token=?,lease_until=clock_timestamp()+interval '90"
                + " seconds' WHERE order_id=(SELECT order_id FROM refund_job WHERE status IN"
                + " ('QUEUED','RETRY','PENDING') AND next_attempt_at<=clock_timestamp() AND"
                + " (lease_until IS NULL OR lease_until<clock_timestamp()) ORDER BY next_attempt_at"
                + " FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *",
            this::map,
            lease);
    if (jobs.isEmpty()) return false;
    var job = jobs.getFirst();
    try {
      RazorpayGateway.Refund refund;
      if (job.providerId() != null) refund = gateway.refund(job.providerId());
      else {
        var payment = gateway.payment(job.paymentId());
        var order =
            jdbc.queryForMap(
                "SELECT provider_order_id FROM checkout_order WHERE id=?", job.orderId());
        if (payment == null
            || !job.paymentId().equals(payment.id())
            || !Objects.equals(order.get("provider_order_id"), payment.orderId())
            || payment.amount() != job.amount()
            || !job.currency().equals(payment.currency())) {
          stop(job, "REVIEW", "Payment identity or amount mismatch");
          return true;
        }
        if ("refunded".equals(payment.status()) && payment.amountRefunded() == job.amount()) {
          stop(job, "PROCESSED", "Full refund already verified with provider");
          return true;
        }
        if (!"captured".equals(payment.status()) || payment.amountRefunded() != 0) {
          stop(job, "REVIEW", "Payment status or partial external refund requires review");
          return true;
        }
        refund = gateway.createRefund(job.paymentId(), job.amount(), job.orderId());
      }
      apply(job, refund, true);
    } catch (Exception ex) {
      // An uncertain create always retries the identical body/key. Never generate a new refund key.
      jdbc.update(
          "UPDATE refund_job SET failures=failures+1,status=CASE WHEN failures>=11 THEN 'FAILED'"
              + " ELSE 'RETRY' END,"
              + " next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(3600,15*power(2,LEAST(failures,8)))::int),"
              + " lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp(),last_error='Provider"
              + " request deferred' WHERE order_id=? AND lease_token=? AND status<>'PROCESSED'",
          job.orderId(),
          job.lease());
    }
    return true;
  }

  private void stop(Job job, String status, String message) {
    jdbc.update(
        "UPDATE refund_job SET"
            + " status=?,last_error=?,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()"
            + " WHERE order_id=? AND lease_token=? AND status<>'PROCESSED'",
        status,
        message,
        job.orderId(),
        job.lease());
  }

  private void apply(Job job, RazorpayGateway.Refund refund, boolean leased) {
    if (refund == null
        || !job.paymentId().equals(refund.paymentId())
        || refund.amount() != job.amount()
        || !job.currency().equals(refund.currency())
        || !job.orderId().toString().equals(refund.receipt())
        || refund.id() == null
        || !refund.id().matches("rfnd_[A-Za-z0-9]{1,64}")
        || (job.providerId() != null && !job.providerId().equals(refund.id()))) {
      if (leased) stop(job, "REVIEW", "Refund identity or amount mismatch");
      return;
    }
    String status =
        switch (refund.status()) {
          case "processed" -> "PROCESSED";
          case "pending" -> "PENDING";
          case "failed" -> "FAILED";
          default -> "REVIEW";
        };
    // A stale worker/webhook cannot regress a completed refund.
    String sql =
        "UPDATE refund_job SET status=?,provider_refund_id=?,lease_token=NULL,lease_until=NULL,"
            + " next_attempt_at=clock_timestamp()+interval '60"
            + " seconds',updated_at=clock_timestamp(),last_error=? WHERE order_id=? AND"
            + " status<>'PROCESSED' AND (provider_refund_id IS NULL OR provider_refund_id=?)";
    var args =
        new ArrayList<Object>(
            Arrays.asList(
                status,
                refund.id(),
                Set.of("FAILED", "REVIEW").contains(status)
                    ? "Provider refund requires follow-up"
                    : null,
                job.orderId(),
                refund.id()));
    if (leased) {
      sql += " AND lease_token=?";
      args.add(job.lease());
    }
    jdbc.update(sql, args.toArray());
  }

  public void webhook(String id) {
    // Signature is checked by CheckoutService. Re-fetch the refund rather than trusting payload
    // fields.
    var refund = gateway.refund(id);
    if (refund == null || !id.equals(refund.id())) return;
    var jobs =
        jdbc.query("SELECT * FROM refund_job WHERE payment_id=?", this::map, refund.paymentId());
    if (!jobs.isEmpty()) apply(jobs.getFirst(), refund, false);
  }
}
