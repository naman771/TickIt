package com.tickit.ticket;

import com.tickit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Organizer ownership and explicit event-scoped staff grants are checked on every request. */
@Service
@RequiredArgsConstructor
public class TicketAccess {
  private final JdbcTemplate jdbc;
  private final CurrentUserService current;

  public long owner(long event) {
    return require(event, false);
  }

  public long scanner(long event) {
    return require(event, true);
  }

  public void scanner(long event, long user) {
    check(event, true, user);
  }

  private long require(long event, boolean staff) {
    long user = current.resolve(null).getId();
    check(event, staff, user);
    return user;
  }

  private void check(long event, boolean staff, long user) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM event e WHERE e.id=? AND (e.organizer_id=?"
                + (staff
                    ? " OR EXISTS(SELECT 1 FROM event_staff s WHERE s.event_id=e.id AND"
                        + " s.user_id=?)"
                    : "")
                + ")",
            Integer.class,
            staff ? new Object[] {event, user, user} : new Object[] {event, user});
    if (n == null || n != 1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event workspace not found");
  }
}
