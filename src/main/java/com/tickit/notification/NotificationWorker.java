package com.tickit.notification;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationWorker {
  private final JdbcTemplate jdbc;
  private final MailTransport transport;
  private final NotificationOutbox outbox;

  @Value("${app.email.enabled:false}")
  private boolean enabled;

  @Value("${app.notifications.scheduled-enabled:true}")
  private boolean scheduled;

  @Scheduled(fixedDelay = 30000, initialDelay = 45000)
  public void run() {
    if (!scheduled) return;
    outbox.recover();
    for (int n = 0; n < 10 && processOne(); n++) {
      /* Bounded queue drain. */
    }
  }

  public boolean processOne() {
    // Disabled delivery leaves durable intents queued. It never claims to have sent mail.
    if (!enabled) return false;
    UUID lease = UUID.randomUUID();
    var rows =
        jdbc.queryForList(
            "UPDATE notification_outbox SET lease_token=?,lease_until=clock_timestamp()+interval"
                + " '120 seconds' WHERE id=(SELECT id FROM notification_outbox WHERE status IN"
                + " ('QUEUED','RETRY') AND next_attempt_at<=clock_timestamp() AND (lease_until IS"
                + " NULL OR lease_until<clock_timestamp()) ORDER BY next_attempt_at FOR UPDATE SKIP"
                + " LOCKED LIMIT 1) RETURNING *",
            lease);
    if (rows.isEmpty()) return false;
    var row = rows.getFirst();
    UUID id = (UUID) row.get("id");
    try {
      transport.send(
          id, (String) row.get("recipient"), (String) row.get("subject"), (String) row.get("body"));
      jdbc.update(
          "UPDATE notification_outbox SET"
              + " status='SENT',attempts=attempts+1,sent_at=clock_timestamp(),"
              + " last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
          id,
          lease);
    } catch (Exception ex) {
      // Mail exceptions can contain recipients, credentials and remote responses: never persist
      // them.
      jdbc.update(
          "UPDATE notification_outbox SET status=CASE WHEN attempts>=7 THEN 'FAILED' ELSE 'RETRY'"
              + " END,"
              + " attempts=attempts+1,next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(3600,30*power(2,LEAST(attempts,7)))::int),"
              + " lease_token=NULL,lease_until=NULL,last_error='Delivery failed; check mail"
              + " configuration or provider status' WHERE id=? AND lease_token=?",
          id,
          lease);
    }
    return true;
  }
}
