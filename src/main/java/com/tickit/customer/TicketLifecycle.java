package com.tickit.customer;

import com.tickit.repository.*;
import com.tickit.service.*;
import java.security.SecureRandom;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Event-row locking serializes transfer, admission and refund intent. Financial ownership is
 * immutable.
 */
@Service
@RequiredArgsConstructor
public class TicketLifecycle {
  private final JdbcTemplate jdbc;
  private final CurrentUserService current;
  private final BookingRepository bookings;
  private final AdmissionHoldRepository admissionHolds;
  private final SeatHoldService holds;
  private static final SecureRandom RANDOM = new SecureRandom();

  private ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  private void eventLock(long event) {
    jdbc.queryForObject("SELECT id FROM event WHERE id=? FOR UPDATE", Long.class, event);
  }

  private Map<String, Object> ticket(UUID ticket) {
    return jdbc
        .queryForList(
            "SELECT t.*,o.id order_id,o.user_id"
                + " purchaser_id,o.event_id,COALESCE(t.holder_id,o.user_id)"
                + " owner_id,i.label,e.minimum_age,e.per_order_limit,e.start_time AT TIME ZONE"
                + " e.timezone starts,(o.status='CONFIRMED' AND o.provider_payment_id IS NOT NULL"
                + " AND e.publication_status='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM refund_job r"
                + " WHERE r.order_id=o.id)) active FROM admission_ticket t JOIN checkout_item i ON"
                + " i.id=t.item_id JOIN checkout_order o ON o.id=i.order_id JOIN event e ON"
                + " e.id=o.event_id WHERE t.id=?",
            ticket)
        .stream()
        .findFirst()
        .orElseThrow(() -> error(404, "Ticket not found"));
  }

  private void transferable(Map<String, Object> row) {
    if (!Boolean.TRUE.equals(row.get("active"))
        || row.get("checked_in_at") != null
        || !((java.sql.Timestamp) row.get("starts"))
            .toInstant()
            .isAfter(
                jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class)
                    .toInstant()))
      throw error(409, "Only unused active tickets for a future event can be transferred");
  }

  @Transactional
  public UUID offer(UUID id, String recipientEmail) {
    long user = current.resolve(null).getId();
    var row = ticket(id);
    if (((Number) row.get("owner_id")).longValue() != user) throw error(404, "Ticket not found");
    eventLock(((Number) row.get("event_id")).longValue());
    row = ticket(id);
    if (((Number) row.get("owner_id")).longValue() != user) throw error(404, "Ticket not found");
    transferable(row);
    // Never create new accounts or bind an unverified email as the admission owner.
    var recipients =
        jdbc.queryForList(
            "SELECT id FROM app_user WHERE lower(email)=lower(?) AND auth_subject IS NOT NULL AND"
                + " auth_issuer IS NOT NULL",
            Long.class,
            recipientEmail.trim());
    if (recipients.size() != 1 || recipients.getFirst() == user)
      throw error(400, "Use another registered TickIt account's verified email");
    jdbc.update(
        "UPDATE ticket_transfer SET status='EXPIRED',resolved_at=clock_timestamp() WHERE"
            + " ticket_id=? AND status='PENDING' AND expires_at<=clock_timestamp()",
        id);
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ticket_transfer WHERE ticket_id=? AND status='PENDING'",
            Integer.class,
            id)
        > 0) throw error(409, "Resolve the pending transfer first");
    // Bound accepted and declined/retried offers without retaining credentials in the audit.
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ticket_transfer WHERE sender_id=? AND"
                + " created_at>clock_timestamp()-interval '1 hour'",
            Integer.class,
            user)
        >= 10) throw error(429, "Please wait before offering another ticket transfer");
    UUID transfer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ticket_transfer(id,ticket_id,sender_id,recipient_id,expires_at)"
            + " VALUES(?,?,?,?,LEAST(clock_timestamp()+interval '24 hours',?))",
        transfer,
        id,
        user,
        recipients.getFirst(),
        row.get("starts"));
    return transfer;
  }

  public List<Map<String, Object>> transfers() {
    long user = current.resolve(null).getId();
    return jdbc.queryForList(
        "SELECT x.id,x.ticket_id,CASE WHEN x.status='PENDING' AND x.expires_at<=clock_timestamp()"
            + " THEN 'EXPIRED' ELSE x.status END"
            + " status,x.expires_at,x.created_at,(x.recipient_id=?) incoming,e.id event_id,e.name"
            + " event_name,e.minimum_age,i.label FROM ticket_transfer x JOIN admission_ticket t ON"
            + " t.id=x.ticket_id JOIN checkout_item i ON i.id=t.item_id JOIN checkout_order o ON"
            + " o.id=i.order_id JOIN event e ON e.id=o.event_id WHERE x.sender_id=? OR"
            + " x.recipient_id=? ORDER BY x.created_at DESC LIMIT 100",
        user,
        user,
        user);
  }

  @Transactional
  public void resolve(UUID id, String action, Integer age) {
    long user = current.resolve(null).getId();
    var rows =
        jdbc.queryForList(
            "SELECT x.*,o.event_id FROM ticket_transfer x JOIN admission_ticket t ON"
                + " t.id=x.ticket_id JOIN checkout_item i ON i.id=t.item_id JOIN checkout_order o"
                + " ON o.id=i.order_id WHERE x.id=? AND (x.sender_id=? OR x.recipient_id=?)",
            id,
            user,
            user);
    if (rows.isEmpty()) throw error(404, "Transfer not found");
    eventLock(((Number) rows.getFirst().get("event_id")).longValue());
    var transfer = jdbc.queryForMap("SELECT * FROM ticket_transfer WHERE id=? FOR UPDATE", id);
    boolean incoming = ((Number) transfer.get("recipient_id")).longValue() == user;
    if (("CANCEL".equals(action) && incoming) || (!"CANCEL".equals(action) && !incoming))
      throw error(404, "Transfer action not available");
    String target =
        "ACCEPT".equals(action) ? "ACCEPTED" : "DECLINE".equals(action) ? "DECLINED" : "CANCELLED";
    if (target.equals(transfer.get("status"))) return;
    if (!"PENDING".equals(transfer.get("status"))
        || !((java.sql.Timestamp) transfer.get("expires_at"))
            .toInstant()
            .isAfter(
                jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class)
                    .toInstant()))
      throw error(409, "This transfer has expired or has already been resolved");
    var ticket = ticket((UUID) transfer.get("ticket_id"));
    if ("ACCEPT".equals(action)) {
      transferable(ticket);
      if (!Objects.equals(
          ((Number) ticket.get("owner_id")).longValue(),
          ((Number) transfer.get("sender_id")).longValue()))
        throw error(409, "Ticket ownership has changed");
      Integer minimum = (Integer) ticket.get("minimum_age");
      if (minimum != null && (age == null || age < minimum))
        throw error(400, "Confirm that the guest meets the event age requirement");
      long event = ((Number) ticket.get("event_id")).longValue();
      if (bookings.countAllTickets(user, event)
              + admissionHolds.activeForUser(user, event)
              + holds.activeForUser(event, user)
              + (((Number) ticket.get("purchaser_id")).longValue() == user ? 0 : 1)
          > Math.min(3, ((Number) ticket.get("per_order_limit")).intValue()))
        throw error(409, "Your per-event ticket limit has been reached");
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      jdbc.update(
          "UPDATE admission_ticket SET holder_id=?,credential=? WHERE id=?",
          user,
          Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
          transfer.get("ticket_id"));
    }
    jdbc.update(
        "UPDATE ticket_transfer SET status=?,resolved_at=clock_timestamp() WHERE id=?", target, id);
  }

  @Transactional
  public void cancelOrder(UUID id) {
    long user = current.resolve(null).getId();
    var orders =
        jdbc.queryForList("SELECT event_id FROM checkout_order WHERE id=? AND user_id=?", id, user);
    if (orders.isEmpty()) throw error(404, "Booking not found");
    eventLock(((Number) orders.getFirst().get("event_id")).longValue());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM refund_job WHERE order_id=? AND reason='CUSTOMER_CANCELLED'",
            Integer.class,
            id)
        == 1) return;
    var eligible =
        jdbc.queryForList(
            "SELECT o.* FROM checkout_order o JOIN event e ON e.id=o.event_id WHERE o.id=? AND"
                + " o.user_id=? AND o.status='CONFIRMED' AND o.provider_payment_id IS NOT NULL AND"
                + " e.publication_status='PUBLISHED' AND o.cancellation_deadline>clock_timestamp()"
                + " AND (e.start_time AT TIME ZONE e.timezone)>clock_timestamp() AND NOT"
                + " EXISTS(SELECT 1 FROM refund_job r WHERE r.order_id=o.id) AND NOT EXISTS(SELECT"
                + " 1 FROM admission_ticket t JOIN checkout_item i ON i.id=t.item_id WHERE"
                + " i.order_id=o.id AND (t.checked_in_at IS NOT NULL OR (t.holder_id IS NOT NULL"
                + " AND t.holder_id<>o.user_id)))",
            id,
            user);
    if (eligible.isEmpty())
      throw error(
          409,
          "This booking is not eligible for self-service cancellation. Check its deadline, ticket"
              + " ownership and entry status");
    var order = eligible.getFirst();
    jdbc.update(
        "INSERT INTO refund_job(order_id,payment_id,amount,currency,reason)"
            + " VALUES(?,?,?,?,'CUSTOMER_CANCELLED')",
        id,
        order.get("provider_payment_id"),
        order.get("amount"),
        order.get("currency"));
    jdbc.update(
        "UPDATE ticket_transfer SET status='CANCELLED',resolved_at=clock_timestamp() WHERE"
            + " status='PENDING' AND ticket_id IN (SELECT t.id FROM admission_ticket t JOIN"
            + " checkout_item i ON i.id=t.item_id WHERE i.order_id=?)",
        id);
    // Admission status derives from refund intent immediately; provider processing remains
    // asynchronous.
  }
}
