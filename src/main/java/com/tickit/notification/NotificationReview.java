package com.tickit.notification;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationReview {
  private final JdbcTemplate jdbc;

  @Transactional
  public void act(UUID id, String action, String reason, String actor) {
    if (!Set.of("RETRY", "SUPPRESS", "RELEASE").contains(action)
        || reason == null
        || reason.isBlank()
        || reason.length() > 500)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Provide a valid action and reason");
    String allowed =
        action.equals("RETRY")
            ? "('FAILED')"
            : action.equals("RELEASE") ? "('HELD')" : "('HELD','QUEUED','RETRY','FAILED')";
    int n =
        jdbc.update(
            "UPDATE notification_outbox SET status=?,attempts=CASE WHEN ?='RETRY' THEN 0 ELSE"
                + " attempts END,"
                + " next_attempt_at=clock_timestamp(),last_error=?,lease_token=NULL,lease_until=NULL"
                + " WHERE id=? AND status IN "
                + allowed
                + " AND (lease_until IS NULL OR lease_until<clock_timestamp())",
            action.equals("RETRY") ? "RETRY" : action.equals("RELEASE") ? "QUEUED" : "SUPPRESSED",
            action,
            action.equals("RETRY")
                ? "Operator requested retry"
                : action.equals("RELEASE")
                    ? "Released after operator review"
                    : "Suppressed after operator review",
            id);
    if (n != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This message is already accepted, suppressed, claimed or ineligible");
    jdbc.update(
        "INSERT INTO notification_operation(notification_id,action,actor,reason) VALUES(?,?,?,?)",
        id,
        action,
        actor,
        reason.trim());
  }
}
