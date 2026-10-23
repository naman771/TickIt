package com.tickit.service;

import com.tickit.model.Event;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class EventSales {
  private EventSales() {}

  public static void requireAge(Event e, Integer age) {
    if (e.getMinimumAge() != null && (age == null || age < e.getMinimumAge() || age > 120))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "All guests must meet the minimum age of "
              + e.getMinimumAge()
              + ". Declare the youngest guest's age.");
  }

  public static void requireOpen(Event e) {
    if (!"PUBLISHED".equals(e.getPublicationStatus()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This event is no longer accepting reservations");
    Instant now = Instant.now();
    ZoneId zone = ZoneId.of(e.getTimezone());
    if (e.getSalesStart() != null && now.isBefore(e.getSalesStart().atZone(zone).toInstant()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Ticket reservations have not opened yet");
    LocalDateTime close = e.getSalesEnd() == null ? e.getStartTime() : e.getSalesEnd();
    if (!now.isBefore(close.atZone(zone).toInstant()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Reservations for this event are closed");
  }
}
