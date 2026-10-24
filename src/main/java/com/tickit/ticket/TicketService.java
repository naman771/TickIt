package com.tickit.ticket;

import com.tickit.service.CurrentUserService;
import com.tickit.service.EventSnapshots;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TicketService {
  private final JdbcTemplate jdbc;
  private final CurrentUserService current;
  private final TicketAccess access;
  private final EventSnapshots snapshots;
  private final TransactionTemplate tx;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String JOIN =
      " FROM admission_ticket t JOIN checkout_item i ON i.id=t.item_id"
          + " JOIN checkout_order o ON o.id=i.order_id JOIN event e ON e.id=o.event_id"
          + " LEFT JOIN refund_job r ON r.order_id=o.id ";
  private static final String ACTIVE =
      "o.status='CONFIRMED' AND o.provider_payment_id IS NOT NULL"
          + " AND e.publication_status='PUBLISHED' AND r.order_id IS NULL";

  public record Ticket(
      UUID id,
      UUID orderId,
      long eventId,
      String label,
      int ordinal,
      String status,
      Instant checkedInAt) {}

  public record Scan(String outcome, String label, Instant checkedInAt) {}

  /** Joins the booking transaction, with the event lock held by settlement. */
  @Transactional
  public void issue(UUID order) {
    var rows =
        jdbc.queryForList(
            "SELECT i.id,i.quantity FROM checkout_item i JOIN checkout_order o ON o.id=i.order_id"
                + " JOIN event e ON e.id=o.event_id WHERE o.id=? AND o.status='CONFIRMED' AND"
                + " o.provider_payment_id IS NOT NULL AND e.publication_status='PUBLISHED' AND NOT"
                + " EXISTS(SELECT 1 FROM refund_job r WHERE r.order_id=o.id)",
            order);
    for (var row : rows)
      for (int n = 1; n <= ((Number) row.get("quantity")).intValue(); n++) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        jdbc.update(
            "INSERT INTO admission_ticket(id,item_id,ordinal,credential) VALUES(?,?,?,?)"
                + " ON CONFLICT(item_id,ordinal) DO NOTHING",
            UUID.randomUUID(),
            row.get("id"),
            n,
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
      }
  }

  public void recoverIssuance() {
    var orders =
        jdbc.queryForList(
            "SELECT DISTINCT o.id,o.event_id FROM checkout_order o JOIN checkout_item i ON"
                + " i.order_id=o.id JOIN event e ON e.id=o.event_id WHERE o.status='CONFIRMED' AND"
                + " o.provider_payment_id IS NOT NULL AND e.publication_status='PUBLISHED' AND NOT"
                + " EXISTS(SELECT 1 FROM refund_job r WHERE r.order_id=o.id) AND (SELECT count(*)"
                + " FROM admission_ticket t WHERE t.item_id=i.id)<i.quantity LIMIT 20");
    for (var o : orders)
      tx.executeWithoutResult(
          s -> {
            jdbc.queryForObject(
                "SELECT id FROM event WHERE id=? FOR UPDATE", Long.class, o.get("event_id"));
            issue((UUID) o.get("id"));
          });
  }

  // No credential is included in history, guest reports, URLs or scan audit records.
  public List<Ticket> forOrder(UUID order) {
    return jdbc.query(
        "SELECT t.*,o.id order_id,o.event_id,i.label,CASE WHEN NOT ("
            + ACTIVE
            + ") THEN 'INACTIVE' WHEN t.checked_in_at IS NOT NULL THEN 'USED' ELSE 'VALID' END"
            + " ticket_status"
            + JOIN
            + " WHERE o.id=? ORDER BY i.id,t.ordinal",
        (r, n) ->
            new Ticket(
                r.getObject("id", UUID.class),
                r.getObject("order_id", UUID.class),
                r.getLong("event_id"),
                r.getString("label"),
                r.getInt("ordinal"),
                r.getString("ticket_status"),
                r.getTimestamp("checked_in_at") == null
                    ? null
                    : r.getTimestamp("checked_in_at").toInstant()),
        order);
  }

  public Map<String, Object> detail(UUID id) {
    long user = current.resolve(null).getId();
    var rows =
        jdbc.queryForList(
            "SELECT o.id order_id,o.event_id,t.credential" + JOIN + " WHERE t.id=? AND o.user_id=?",
            id,
            user);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
    var row = rows.getFirst();
    var ticket =
        forOrder((UUID) row.get("order_id")).stream()
            .filter(t -> t.id().equals(id))
            .findFirst()
            .orElseThrow();
    var data = new LinkedHashMap<String, Object>();
    data.put("ticket", ticket);
    data.put("event", snapshots.get(ticket.eventId()));
    // A used or invalidated ticket remains visible as history but is never presented as valid
    // entry.
    data.put(
        "credential",
        "VALID".equals(ticket.status()) ? "tickit:v1:" + row.get("credential") : null);
    return data;
  }

  @Transactional
  public Scan scan(long event, String credential) {
    // Provision/authorize before locking; an unrelated account cannot hold the event lock during
    // an identity-provider request. Recheck the grant under the lock to serialize revocation.
    long actor = access.scanner(event);
    var exists = jdbc.queryForList("SELECT id FROM event WHERE id=? FOR UPDATE", event);
    if (exists.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event workspace not found");
    access.scanner(event, actor);
    String raw =
        credential != null && credential.matches("tickit:v1:[A-Za-z0-9_-]{43}")
            ? credential.substring(10)
            : "";
    var rows =
        jdbc.queryForList(
            "SELECT t.id,t.checked_in_at,i.label,("
                + ACTIVE
                + ") active, e.start_time AT TIME ZONE e.timezone starts,"
                + " (COALESCE(e.end_time,e.start_time+interval '8 hours')) AT TIME ZONE e.timezone"
                + " ends"
                + JOIN
                + " WHERE t.credential=? AND o.event_id=?",
            raw,
            event);
    UUID ticket = null;
    String outcome = "INVALID", label = null;
    Instant checked = null;
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      ticket = (UUID) row.get("id");
      label = (String) row.get("label");
      var timestamp = (java.sql.Timestamp) row.get("checked_in_at");
      checked = timestamp == null ? null : timestamp.toInstant();
      var now =
          jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
      Instant starts = ((java.sql.Timestamp) row.get("starts")).toInstant(),
          ends = ((java.sql.Timestamp) row.get("ends")).toInstant();
      if (!Boolean.TRUE.equals(row.get("active"))) outcome = "INACTIVE";
      else if (checked != null) outcome = "ALREADY_USED";
      else if (now.isBefore(starts.minusSeconds(6 * 3600))) outcome = "TOO_EARLY";
      else if (now.isAfter(ends)) outcome = "ENDED";
      else {
        checked =
            jdbc.queryForObject(
                    "UPDATE admission_ticket SET checked_in_at=clock_timestamp(),checked_in_by=?"
                        + " WHERE id=? AND checked_in_at IS NULL RETURNING checked_in_at",
                    java.sql.Timestamp.class,
                    actor,
                    ticket)
                .toInstant();
        outcome = "ADMITTED";
      }
    }
    jdbc.update(
        "INSERT INTO admission_scan(event_id,ticket_id,actor_id,outcome) VALUES(?,?,?,?)",
        event,
        ticket,
        actor,
        outcome);
    return new Scan(outcome, label, checked);
  }
}
