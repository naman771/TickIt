package com.tickit.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TicketDashboard {
  private final JdbcTemplate jdbc;

  public Set<Long> hiddenEvents(long user) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT event_id FROM customer_hidden_event WHERE user_id=?", Long.class, user));
  }

  @Transactional
  public void remove(long user, long event) {
    // Serialize eligibility with organizer changes, and conceal events this account never booked.
    var statuses =
        jdbc.queryForList(
            "SELECT publication_status FROM event e WHERE e.id=? AND (EXISTS (SELECT 1 FROM booking"
                + " b WHERE b.event_id=e.id AND b.user_id=?) OR EXISTS (SELECT 1 FROM"
                + " checkout_order o WHERE o.event_id=e.id AND o.user_id=?)) FOR UPDATE",
            String.class,
            event,
            user,
            user);
    if (statuses.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event tickets not found");
    if (!Set.of("CANCELLED", "DELETED").contains(statuses.getFirst()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only cancelled or deleted event tickets can be removed");
    jdbc.update(
        "INSERT INTO customer_hidden_event(user_id,event_id) VALUES(?,?)"
            + " ON CONFLICT(user_id,event_id) DO NOTHING",
        user,
        event);
  }
}
