package com.tickit.service;

import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Account-owned ticket views retain event details even after catalog removal. */
@Component
@RequiredArgsConstructor
public class EventSnapshots {
  private final JdbcTemplate jdbc;

  public record Summary(
      long id,
      String name,
      String slug,
      LocalDateTime startTime,
      Instant startInstant,
      Instant endInstant,
      String timezone,
      String status,
      String changeNote,
      boolean scheduleChanged,
      String venueName,
      String venueLocation,
      String city,
      String imageUrl) {}

  public Summary get(long id) {
    return jdbc.queryForObject(
        "SELECT e.*,COALESCE((SELECT v.name FROM venue v WHERE v.event_id=e.id ORDER BY v.id LIMIT"
            + " 1),'') venue_name,COALESCE((SELECT v.location FROM venue v WHERE v.event_id=e.id"
            + " ORDER BY v.id LIMIT 1),'') venue_location FROM event e WHERE e.id=?",
        (r, n) -> {
          var start = r.getTimestamp("start_time").toLocalDateTime();
          var zone = r.getString("timezone");
          return new Summary(
              id,
              r.getString("name"),
              r.getString("slug"),
              start,
              start.atZone(ZoneId.of(zone)).toInstant(),
              r.getTimestamp("end_time") == null
                  ? null
                  : r.getTimestamp("end_time")
                      .toLocalDateTime()
                      .atZone(ZoneId.of(zone))
                      .toInstant(),
              zone,
              r.getString("publication_status"),
              r.getString("change_note"),
              r.getTimestamp("schedule_changed_at") != null,
              r.getString("venue_name"),
              r.getString("venue_location"),
              r.getString("city"),
              r.getString("image_url"));
        },
        id);
  }
}
