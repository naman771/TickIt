package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickit.model.Event;
import com.tickit.repository.EventRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventDiscoveryTest {
  @Autowired EventRepository events;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  Event event(String status, Instant start, Instant end, String zone) {
    return events.saveAndFlush(
        Event.builder()
            .name("Discovery " + UUID.randomUUID())
            .publicationStatus(status)
            .timezone(zone)
            .startTime(LocalDateTime.ofInstant(start, ZoneId.of(zone)))
            .endTime(end == null ? null : LocalDateTime.ofInstant(end, ZoneId.of(zone)))
            .build());
  }

  Set<Long> catalog() throws Exception {
    var body =
        mvc.perform(get("/api/events"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Set<Long> ids = new HashSet<>();
    json.readTree(body).forEach(e -> ids.add(e.get("id").asLong()));
    return ids;
  }

  @Test
  void exploreIncludesUpcomingAndOngoingButHidesEndedAndUnpublishedEvents() throws Exception {
    Instant now = Instant.now();
    var upcoming = event("PUBLISHED", now.plusSeconds(86400), null, "Asia/Kolkata");
    var ongoing = event("PUBLISHED", now.minusSeconds(3600), now.plusSeconds(3600), "UTC");
    var ongoingWithoutEnd = event("PUBLISHED", now.minusSeconds(3600), null, "Asia/Kolkata");
    var ended = event("PUBLISHED", now.minusSeconds(7200), now.minusSeconds(60), "Asia/Kolkata");
    var endedWithoutEnd = event("PUBLISHED", now.minusSeconds(9 * 3600), null, "UTC");
    var hidden = new ArrayList<Event>(List.of(ended, endedWithoutEnd));
    for (String state : List.of("DRAFT", "CANCELLED", "DELETED"))
      hidden.add(event(state, now.plusSeconds(86400), null, "UTC"));
    var ids = catalog();
    for (var visible : List.of(upcoming, ongoing, ongoingWithoutEnd))
      assertTrue(ids.contains(visible.getId()), visible.getName());
    for (var absent : hidden) assertFalse(ids.contains(absent.getId()), absent.getName());
  }

  @Test
  void exactEndBoundaryUsesEachEventsTimezoneIncludingDst() {
    Instant now = Instant.parse("2030-07-01T12:00:00Z");
    var expired = new ArrayList<Event>();
    var remaining = new ArrayList<Event>();
    for (String zone : List.of("UTC", "Asia/Kolkata", "America/New_York")) {
      expired.add(event("PUBLISHED", now.minusSeconds(3600), now, zone));
      expired.add(event("PUBLISHED", now.minusSeconds(3600), now.minusSeconds(1), zone));
      remaining.add(event("PUBLISHED", now.minusSeconds(3600), now.plusSeconds(1), zone));
      expired.add(event("PUBLISHED", now.minusSeconds(8 * 3600), null, zone));
    }
    Set<Long> ids = new HashSet<>();
    events.findDiscoverable(now).forEach(e -> ids.add(e.getId()));
    expired.forEach(e -> assertFalse(ids.contains(e.getId()), e.getTimezone()));
    remaining.forEach(e -> assertTrue(ids.contains(e.getId()), e.getTimezone()));
  }

  @Test
  void hidingEndedEventsFromExploreDoesNotDeleteTheirDetails() throws Exception {
    Instant now = Instant.now();
    var past = event("PUBLISHED", now.minusSeconds(7200), now.minusSeconds(60), "UTC");
    assertFalse(catalog().contains(past.getId()));
    mvc.perform(get("/api/events/" + past.getId())).andExpect(status().isOk());
    assertTrue(events.existsById(past.getId()));
  }
}
