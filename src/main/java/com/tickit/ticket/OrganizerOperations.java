package com.tickit.ticket;

import com.tickit.service.CurrentUserService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrganizerOperations {
  private final JdbcTemplate jdbc;
  private final TicketAccess access;
  private final CurrentUserService current;

  public List<Map<String, Object>> entryEvents() {
    long user = current.resolve(null).getId();
    return jdbc.queryForList(
        "SELECT e.id,e.name,e.start_time AS \"startTime\",e.timezone,e.publication_status status"
            + " FROM event e WHERE (e.organizer_id=? OR EXISTS(SELECT 1 FROM event_staff s WHERE"
            + " s.event_id=e.id AND s.user_id=?)) AND e.publication_status<>'DRAFT' ORDER BY"
            + " e.start_time DESC LIMIT 100",
        user,
        user);
  }

  public Map<String, Object> report(long event, int page) {
    access.owner(event);
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "financials",
        jdbc.queryForMap(
            "SELECT count(*) FILTER(WHERE provider_payment_id IS NOT NULL) captured_orders,"
                + " COALESCE(sum(amount) FILTER(WHERE provider_payment_id IS NOT NULL),0)"
                + " captured_amount, COALESCE((SELECT sum(amount) FROM refund_job r WHERE"
                + " r.order_id IN (SELECT id FROM checkout_order WHERE event_id=?) AND"
                + " r.status='PROCESSED'),0) refunded_amount, COALESCE((SELECT sum(amount) FROM"
                + " refund_job r WHERE r.order_id IN (SELECT id FROM checkout_order WHERE"
                + " event_id=?) AND r.status<>'PROCESSED'),0) outstanding_refund_amount, 'INR'"
                + " currency FROM checkout_order WHERE event_id=?",
            event,
            event,
            event));
    result.put(
        "admissions",
        jdbc.queryForMap(
            "SELECT count(*) issued,count(*) FILTER(WHERE t.checked_in_at IS NOT NULL) checked_in"
                + " FROM admission_ticket t JOIN checkout_item i ON i.id=t.item_id JOIN"
                + " checkout_order o ON o.id=i.order_id WHERE o.event_id=?",
            event));
    result.put("page", page);
    result.put("guests", guests(event, page));
    result.put("staff", staff(event));
    result.put(
        "scans",
        jdbc.queryForList(
            "SELECT s.outcome,s.created_at,u.full_name actor FROM admission_scan s JOIN app_user u"
                + " ON u.id=s.actor_id WHERE s.event_id=? ORDER BY s.id DESC LIMIT 50",
            event));
    return result;
  }

  public List<Map<String, Object>> guests(long event, int page) {
    access.owner(event);
    if (page < 0 || page > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid guest page");
    return jdbc.queryForList(
        "SELECT t.id,t.ordinal,i.label,o.id order_id,u.full_name,u.email,t.checked_in_at, o.status"
            + " order_status,e.publication_status event_status,r.status refund_status FROM"
            + " admission_ticket t JOIN checkout_item i ON i.id=t.item_id JOIN checkout_order o ON"
            + " o.id=i.order_id JOIN app_user u ON u.id=COALESCE(t.holder_id,o.user_id) JOIN event"
            + " e ON e.id=o.event_id LEFT JOIN refund_job r ON r.order_id=o.id WHERE o.event_id=?"
            + " ORDER BY i.id,t.ordinal LIMIT 100 OFFSET ?",
        event,
        page * 100);
  }

  public List<Map<String, Object>> staff(long event) {
    access.owner(event);
    return jdbc.queryForList(
        "SELECT u.id,u.full_name,u.email,s.created_at FROM event_staff s JOIN app_user u"
            + " ON u.id=s.user_id WHERE s.event_id=? ORDER BY s.created_at",
        event);
  }

  @Transactional
  public void grant(long event, String email) {
    lock(event);
    long owner = access.owner(event);
    var users =
        jdbc.queryForList(
            "SELECT id FROM app_user WHERE lower(email)=lower(?) AND auth_subject IS NOT NULL",
            email.trim());
    if (users.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Ask this person to sign in to TickIt with this verified email first");
    long user = ((Number) users.getFirst().get("id")).longValue();
    if (user == owner)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The event owner already has check-in access");
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM event_staff WHERE event_id=?", Integer.class, event);
    if (count != null && count >= 25)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This event already has 25 staff members");
    jdbc.update(
        "INSERT INTO event_staff(event_id,user_id,granted_by) VALUES(?,?,?) ON CONFLICT DO NOTHING",
        event,
        user,
        owner);
  }

  @Transactional
  public void revoke(long event, long user) {
    lock(event);
    access.owner(event);
    jdbc.update("DELETE FROM event_staff WHERE event_id=? AND user_id=?", event, user);
  }

  private void lock(long event) {
    jdbc.queryForList("SELECT id FROM event WHERE id=? FOR UPDATE", event);
  }
}
