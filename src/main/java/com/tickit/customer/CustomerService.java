package com.tickit.customer;

import com.tickit.notification.NotificationOutbox;
import com.tickit.service.CurrentUserService;
import com.tickit.service.DeveloperAccess;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerService {
  private final JdbcTemplate jdbc;
  private final CurrentUserService current;
  private final DeveloperAccess developer;
  private final NotificationOutbox notifications;

  private long user() {
    return current.resolve(null).getId();
  }

  private ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  private void lockUser(long user) {
    jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, user);
  }

  private Map<String, Object> owned(UUID order, long user) {
    return jdbc
        .queryForList(
            "SELECT o.*,e.publication_status FROM checkout_order o JOIN event e ON e.id=o.event_id"
                + " WHERE o.id=? AND o.user_id=?",
            order,
            user)
        .stream()
        .findFirst()
        .orElseThrow(() -> error(404, "Booking not found"));
  }

  public List<Long> saved() {
    return jdbc.queryForList(
        "SELECT event_id FROM saved_event WHERE user_id=? ORDER BY created_at DESC",
        Long.class,
        user());
  }

  @Transactional
  public void save(long event) {
    long user = user();
    lockUser(user);
    if (jdbc.queryForObject(
            "SELECT count(*) FROM event WHERE id=? AND publication_status='PUBLISHED'",
            Integer.class,
            event)
        != 1) throw error(404, "Event not found");
    if (jdbc.queryForObject("SELECT count(*) FROM saved_event WHERE user_id=?", Integer.class, user)
            >= 100
        && jdbc.queryForObject(
                "SELECT count(*) FROM saved_event WHERE user_id=? AND event_id=?",
                Integer.class,
                user,
                event)
            == 0) throw error(409, "You can save up to 100 events");
    jdbc.update(
        "INSERT INTO saved_event(user_id,event_id) VALUES(?,?) ON CONFLICT DO NOTHING",
        user,
        event);
  }

  public void unsave(long event) {
    jdbc.update("DELETE FROM saved_event WHERE user_id=? AND event_id=?", user(), event);
  }

  public List<Map<String, Object>> requests() {
    return jdbc.queryForList(
        "SELECT id,order_id,category,message,status,reply,created_at,updated_at FROM"
            + " customer_support_request WHERE user_id=? ORDER BY created_at DESC LIMIT 100",
        user());
  }

  @Transactional
  public UUID support(UUID order, String category, String message) {
    long user = user();
    lockUser(user);
    owned(order, user);
    if (jdbc.queryForObject(
            "SELECT count(*) FROM customer_support_request WHERE user_id=? AND"
                + " created_at>clock_timestamp()-interval '1 hour'",
            Integer.class,
            user)
        >= 5) throw error(429, "Please wait before opening another support request");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO customer_support_request(id,user_id,order_id,category,message)"
            + " VALUES(?,?,?,?,?)",
        id,
        user,
        order,
        category,
        message.trim());
    return id;
  }

  @Transactional
  public void resend(UUID order) {
    long user = user();
    lockUser(user);
    var row = owned(order, user);
    if (!"CONFIRMED".equals(row.get("status"))
        || !"PUBLISHED".equals(row.get("publication_status"))
        || row.get("provider_payment_id") == null
        || jdbc.queryForObject(
                "SELECT count(*) FROM refund_job WHERE order_id=?", Integer.class, order)
            > 0) throw error(409, "Only active confirmed bookings can be resent");
    if (jdbc.queryForObject(
                "SELECT count(*) FROM confirmation_resend WHERE user_id=? AND"
                    + " created_at>clock_timestamp()-interval '1 hour'",
                Integer.class,
                user)
            >= 3
        || jdbc.queryForObject(
                "SELECT count(*) FROM confirmation_resend WHERE order_id=? AND"
                    + " created_at>clock_timestamp()-interval '5 minutes'",
                Integer.class,
                order)
            > 0) throw error(429, "Please wait before requesting another confirmation email");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO confirmation_resend(id,user_id,order_id) VALUES(?,?,?)", id, user, order);
    notifications.resend(order, id);
  }

  public List<Map<String, Object>> review() {
    developer.requireDeveloper();
    return jdbc.queryForList(
        "SELECT id,order_id,category,message,status,reply,created_at,updated_at FROM"
            + " customer_support_request ORDER BY CASE WHEN status='OPEN' THEN 0 ELSE 1"
            + " END,created_at DESC LIMIT 100");
  }

  public void reply(UUID id, String reply) {
    developer.requireDeveloper();
    if (jdbc.update(
            "UPDATE customer_support_request SET"
                + " reply=?,status='RESOLVED',updated_at=clock_timestamp() WHERE id=?",
            reply.trim(),
            id)
        != 1) throw error(404, "Request not found");
  }
}
