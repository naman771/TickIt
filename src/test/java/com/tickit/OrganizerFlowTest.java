package com.tickit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.dto.OrganizerEventRequest;
import com.tickit.model.*;
import com.tickit.repository.*;
import com.tickit.service.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "app.auth.mode=catalog",
      "app.rate-limit.capacity=10000",
      "app.auth.allowed-origins=http://localhost:8083"
    })
@AutoConfigureMockMvc
class OrganizerFlowTest {
  @Autowired OrganizerService organizer;
  @Autowired AdmissionService admission;
  @Autowired EventRepository events;
  @Autowired SeatRepository seats;
  @Autowired UserRepository users;
  @Autowired AdmissionPoolRepository pools;
  @Autowired AdmissionTicketTypeRepository types;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean CurrentUserService current;
  AppUser owner, other;

  @BeforeEach
  void setup() {
    owner = user();
    other = user();
    when(current.resolve(any())).thenReturn(owner);
    organizer.enroll("Independent Organizer");
  }

  AppUser user() {
    return users.saveAndFlush(
        AppUser.builder()
            .email("organizer_" + UUID.randomUUID() + "@tickit.test")
            .fullName("Organizer test")
            .build());
  }

  Map<String, Object> payload(String mode) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("name", "An evening with the city");
    d.put("description", "An independently organized cultural evening for our community.");
    d.put("category", "COMMUNITY");
    d.put("admissionMode", mode);
    d.put("venueName", "Independent Hall");
    d.put("address", "12 Park Road");
    d.put("city", "Mumbai");
    d.put("startTime", LocalDateTime.now().plusDays(5).withNano(0));
    d.put("endTime", LocalDateTime.now().plusDays(5).plusHours(3).withNano(0));
    d.put("timezone", "Asia/Kolkata");
    d.put("guestCapacity", 20);
    d.put("imageUrl", "");
    d.put("stageLabel", "Main stage");
    d.put("stageX", 400);
    d.put("stageY", 20);
    d.put("perOrderLimit", 3);
    d.put(
        "seats",
        mode.equals("GENERAL")
            ? List.of()
            : List.of(seat("Main", 1, 80, 130), seat("Main", 2, 108, 130)));
    d.put("pools", mode.equals("SEATED") ? List.of() : List.of(pool(3)));
    return d;
  }

  Map<String, Object> seat(String section, int number, int x, int y) {
    return Map.of(
        "section", section, "row", "A", "number", number, "x", x, "y", y, "price", "500.00");
  }

  Map<String, Object> pool(int capacity) {
    return Map.of(
        "name",
        "Standing floor",
        "capacity",
        capacity,
        "tickets",
        List.of(
            Map.of("name", "Early Bird", "price", "300.00", "quota", capacity),
            Map.of("name", "Regular", "price", "500.00", "quota", capacity)));
  }

  OrganizerEventRequest request(Map<String, Object> data) {
    return json.convertValue(data, OrganizerEventRequest.class);
  }

  Long id(Map<String, Object> view) {
    return ((Number) view.get("id")).longValue();
  }

  Map<String, Object> save(Map<String, Object> p) {
    return organizer.save(null, request(p));
  }

  Map<String, Object> publish(Map<String, Object> p) {
    var d = save(p);
    return organizer.publish(id(d), ((Number) d.get("version")).longValue());
  }

  @Test
  void draftIsPrivateAcrossAllPublicRoutes() throws Exception {
    var d = save(payload("MIXED"));
    Long id = id(d);
    Long seat = seats.findByVenueEventId(id).get(0).getId();
    mvc.perform(get("/api/events/" + id)).andExpect(status().isNotFound());
    mvc.perform(get("/api/events/" + id + "/seats")).andExpect(status().isNotFound());
    mvc.perform(get("/api/events/" + id + "/admission")).andExpect(status().isNotFound());
    var publicEvents =
        json.readTree(
            mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(publicEvents.findValuesAsString("id")).doesNotContain(id.toString());
    mvc.perform(
            post("/api/holds")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":" + id + ",\"seatId\":" + seat + "}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ownerCanPublishCustomLayoutAndPublicDataUsesItsOwnCoordinates() throws Exception {
    var data = payload("SEATED");
    data.put("seats", List.of(seat("Balcony", 1, 220, 310), seat("Floor", 1, 440, 150)));
    var d = publish(data);
    Long id = id(d);
    mvc.perform(get("/api/events/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("INR"))
        .andExpect(jsonPath("$.category").value("COMMUNITY"));
    String body =
        mvc.perform(get("/api/events/" + id + "/seats"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("Balcony", "Floor", "220", "310", "440", "150");
  }

  @Test
  void anotherOrganizerCannotReadEditOrPublishAnEvent() throws Exception {
    var d = save(payload("SEATED"));
    when(current.resolve(any())).thenReturn(other);
    organizer.enroll("Another organizer");
    mvc.perform(get("/api/organizer/events/" + id(d)).with(jwt())).andExpect(status().isNotFound());
    mvc.perform(
            put("/api/organizer/events/" + id(d))
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload("SEATED"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/organizer/events/" + id(d) + "/publish")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void organizerDoesNotReceiveDeveloperAccessAndAnonymousCannotCreate() throws Exception {
    mvc.perform(
            get("/api/developer/overview")
                .with(jwt().jwt(j -> j.subject("organizer_is_not_developer"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/organizer/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload("SEATED"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void profileEnrollmentIsRequired() {
    when(current.resolve(any())).thenReturn(other);
    assertThatThrownBy(() -> organizer.save(null, request(payload("SEATED"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void layoutOnlyUpdatesAdvanceRevisionAndStaleDraftCannotOverwrite() {
    var p = payload("SEATED");
    var d = save(p);
    Long id = id(d);
    p.put("version", d.get("version"));
    p.put("seats", List.of(seat("Custom", 7, 100, 450)));
    var updated = organizer.save(id, request(p));
    assertThat(((Number) updated.get("version")).longValue())
        .isGreaterThan(((Number) d.get("version")).longValue());
    assertThat(seats.findByVenueEventId(id)).hasSize(1);
    assertThatThrownBy(() -> organizer.save(id, request(p)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void publishedEditsPreserveSeatIdentityAndOtherEventsAreIndependent() {
    var first = publish(payload("SEATED"));
    var p = new LinkedHashMap<String, Object>(first);
    p.put("version", first.get("version"));
    var oldIds = seats.findByVenueEventId(id(first)).stream().map(Seat::getId).toList();
    organizer.save(id(first), request(p));
    assertThat(seats.findByVenueEventId(id(first)).stream().map(Seat::getId).toList())
        .isEqualTo(oldIds);
    var second = payload("SEATED");
    second.put("seats", List.of(seat("Different", 3, 600, 500)));
    var d = publish(second);
    assertThat(seats.findByVenueEventId(id(first))).hasSize(2);
    assertThat(seats.findByVenueEventId(id(d)).get(0).getX()).isEqualTo(600);
  }

  @Test
  void serverRejectsCapacityOverflowDuplicateSeatsAndWrongAdmissionFormat() throws Exception {
    var p = payload("MIXED");
    p.put("guestCapacity", 4);
    mvc.perform(
            post("/api/organizer/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(p)))
        .andExpect(status().isBadRequest());
    p = payload("SEATED");
    p.put("seats", List.of(seat("Main", 1, 20, 100), seat("Main", 1, 40, 100)));
    final var duplicate = p;
    assertThatThrownBy(() -> save(duplicate)).hasMessageContaining("Duplicate seat label");
    p = payload("GENERAL");
    p.put("seats", List.of(seat("Main", 1, 20, 100)));
    final var wrong = p;
    assertThatThrownBy(() -> save(wrong)).hasMessageContaining("cannot contain assigned seats");
  }

  @Test
  void invalidLayoutBoundsAndPricesAreRejectedByApi() throws Exception {
    var p = payload("SEATED");
    p.put("seats", List.of(seat("Main", 1, 1001, 20)));
    mvc.perform(
            post("/api/organizer/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(p)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void incompleteLayoutAndPastEventCannotBePublished() {
    var p = payload("SEATED");
    p.put("seats", List.of());
    var draft = save(p);
    assertThatThrownBy(
            () -> organizer.publish(id(draft), ((Number) draft.get("version")).longValue()))
        .hasMessageContaining("seat layout");
    p = payload("GENERAL");
    p.put("startTime", LocalDateTime.now().minusDays(1));
    p.put("endTime", null);
    var past = save(p);
    assertThatThrownBy(
            () -> organizer.publish(id(past), ((Number) past.get("version")).longValue()))
        .hasMessageContaining("future");
  }

  @Test
  void concurrentPriceTiersShareOneCapacityAndExpiredReservationsReleaseIt() throws Exception {
    Long event = id(publish(payload("GENERAL")));
    var pool = pools.findByEventIdOrderById(event).get(0);
    var tier = types.findByPoolIdOrderById(pool.getId());
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    var accepted = new AtomicInteger();
    List<Future<?>> tasks = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      final int n = i;
      tasks.add(
          executor.submit(
              () -> {
                start.await();
                try {
                  admission.hold(event, tier.get(n % 2).getId(), 1, null);
                  accepted.incrementAndGet();
                } catch (ResponseStatusException e) {
                  assertThat(e.getStatusCode().value()).isEqualTo(409);
                }
                return null;
              }));
    }
    start.countDown();
    for (var task : tasks) task.get(20, TimeUnit.SECONDS);
    executor.shutdownNow();
    assertThat(accepted.get()).isEqualTo(3);
    assertThat(((Number) admission.availability(event).get(0).get("available")).intValue())
        .isZero();
    jdbc.update(
        "UPDATE admission_hold SET expires_at=clock_timestamp()-interval '1 second' WHERE"
            + " event_id=?",
        event);
    assertThat(((Number) admission.availability(event).get(0).get("available")).intValue())
        .isEqualTo(3);
    var held = admission.hold(event, tier.get(0).getId(), 3, null);
    admission.release((UUID) held.get("id"));
    assertThat(((Number) admission.availability(event).get(0).get("available")).intValue())
        .isEqualTo(3);
  }

  @Test
  void reservationOwnershipPerAccountLimitAndSalesWindowAreEnforced() {
    var p = payload("GENERAL");
    p.put("perOrderLimit", 2);
    Long event = id(publish(p));
    Long tier =
        types
            .findByPoolIdOrderById(pools.findByEventIdOrderById(event).get(0).getId())
            .get(0)
            .getId();
    assertThatThrownBy(() -> admission.hold(event, tier, 3, null))
        .hasMessageContaining("per-order limit");
    var held = admission.hold(event, tier, 2, null);
    assertThatThrownBy(() -> admission.hold(event, tier, 1, null))
        .hasMessageContaining("per-account");
    when(current.resolve(any())).thenReturn(other);
    assertThatThrownBy(() -> admission.release((UUID) held.get("id"))).hasMessageContaining("403");
    when(current.resolve(any())).thenReturn(owner);
    p = payload("GENERAL");
    p.put("salesStart", LocalDateTime.now().plusDays(1));
    Long future = id(publish(p));
    Long futureType =
        types
            .findByPoolIdOrderById(pools.findByEventIdOrderById(future).get(0).getId())
            .get(0)
            .getId();
    assertThatThrownBy(() -> admission.hold(future, futureType, 1, null))
        .hasMessageContaining("not opened");
  }

  @Test
  void corsAllowsOnlyConfiguredOrganizerOrigins() throws Exception {
    mvc.perform(
            options("/api/organizer/events")
                .header("Origin", "http://localhost:8083")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8083"));
    mvc.perform(
            options("/api/organizer/events")
                .header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void optionalAgePolicyPersistsAndAppliesToSeatAndStandingHolds() throws Exception {
    var p = payload("MIXED");
    p.put("minimumAge", 18);
    var published = publish(p);
    Long event = id(published);
    mvc.perform(get("/api/events/" + event)).andExpect(jsonPath("$.minimumAge").value(18));
    Long seat = seats.findByVenueEventId(event).get(0).getId();
    var request = new LinkedHashMap<String, Object>();
    request.put("eventId", event);
    request.put("seatId", seat);
    mvc.perform(
            post("/api/holds")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
    request.put("attendeeAge", 17);
    mvc.perform(
            post("/api/holds")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
    request.put("attendeeAge", 18);
    mvc.perform(
            post("/api/holds")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isOk());
    Long tier =
        types
            .findByPoolIdOrderById(pools.findByEventIdOrderById(event).get(0).getId())
            .get(0)
            .getId();
    assertThatThrownBy(() -> admission.hold(event, tier, 1, 17))
        .hasMessageContaining("minimum age");
    var hold = admission.hold(event, tier, 2, 18);
    assertThatThrownBy(() -> admission.hold(event, tier, 1, 18))
        .hasMessageContaining("per-account");
    admission.release(java.util.UUID.fromString(hold.get("id").toString()));
  }

  @Test
  void organizerCannotConfigureInvalidAge() throws Exception {
    var p = payload("SEATED");
    p.put("minimumAge", 121);
    mvc.perform(
            post("/api/organizer/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(p)))
        .andExpect(status().isBadRequest());
  }

  @Autowired SeatHoldService seatHolds;

  @Test
  void eventCountsAndStartingPriceExcludeHeldSeatsAndExhaustedTicketTiers() throws Exception {
    var p = payload("MIXED");
    p.put(
        "seats",
        List.of(
            Map.of(
                "section", "Main", "row", "A", "number", 1, "x", 80, "y", 130, "price", "100.00"),
            seat("Main", 2, 108, 130)));
    p.put(
        "pools",
        List.of(
            Map.of(
                "name",
                "Floor",
                "capacity",
                3,
                "tickets",
                List.of(
                    Map.of("name", "Early", "price", "300.00", "quota", 1),
                    Map.of("name", "Regular", "price", "500.00", "quota", 1)))));
    Long event = id(publish(p));
    mvc.perform(get("/api/events/" + event)).andExpect(jsonPath("$.availableTickets").value(4));
    var rows = seats.findByVenueEventId(event);
    Long cheap =
        rows.stream().filter(s -> s.getSeatNumber() == 1).findFirst().orElseThrow().getId();
    Long regular =
        rows.stream().filter(s -> s.getSeatNumber() == 2).findFirst().orElseThrow().getId();
    try {
      org.junit.jupiter.api.Assertions.assertTrue(
          seatHolds.acquireHold(event, cheap, owner.getId()));
      Long tier =
          types
              .findByPoolIdOrderById(pools.findByEventIdOrderById(event).get(0).getId())
              .get(0)
              .getId();
      var ga = admission.hold(event, tier, 1, null);
      mvc.perform(get("/api/events/" + event))
          .andExpect(jsonPath("$.availableSeats").value(1))
          .andExpect(jsonPath("$.availableTickets").value(2))
          .andExpect(jsonPath("$.fromPrice").value(500));
      org.junit.jupiter.api.Assertions.assertTrue(
          seatHolds.acquireHold(event, regular, owner.getId()));
      admission.release((UUID) ga.get("id"));
    } finally {
      for (var row : rows) seatHolds.releaseHold(event, row.getId());
    }
  }

  @Test
  void nullInventoryElementsAndOverlappingSeatRectanglesAreRejected() throws Exception {
    for (String field : List.of("seats", "pools")) {
      var p = payload("MIXED");
      p.put(field, java.util.Arrays.asList((Object) null));
      mvc.perform(
              post("/api/organizer/events")
                  .with(jwt())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(p)))
          .andExpect(status().isBadRequest());
    }
    var p = payload("SEATED");
    p.put("seats", List.of(seat("Main", 1, 80, 130), seat("Main", 2, 90, 140)));
    mvc.perform(
            post("/api/organizer/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(p)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentMixedAdmissionCannotBypassAccountLimit() throws Exception {
    var p = payload("MIXED");
    p.put(
        "seats",
        List.of(
            seat("Main", 1, 80, 130),
            seat("Main", 2, 108, 130),
            seat("Main", 3, 136, 130),
            seat("Main", 4, 164, 130)));
    p.put("pools", List.of(pool(10)));
    Long event = id(publish(p));
    var rows = seats.findByVenueEventId(event);
    Long tier =
        types
            .findByPoolIdOrderById(pools.findByEventIdOrderById(event).get(0).getId())
            .get(0)
            .getId();
    var executor = Executors.newFixedThreadPool(6);
    var start = new CountDownLatch(1);
    List<Future<?>> attempts = new ArrayList<>();
    try {
      for (var row : rows)
        attempts.add(
            executor.submit(
                () -> {
                  start.await();
                  int status =
                      mvc.perform(
                              post("/api/holds")
                                  .with(jwt())
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(
                                      json.writeValueAsString(
                                          Map.of("eventId", event, "seatId", row.getId()))))
                          .andReturn()
                          .getResponse()
                          .getStatus();
                  assertThat(status).isIn(200, 409);
                  return null;
                }));
      for (int i = 0; i < 8; i++)
        attempts.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    admission.hold(event, tier, 1, null);
                  } catch (ResponseStatusException conflict) {
                    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                  }
                  return null;
                }));
      start.countDown();
      for (var attempt : attempts) attempt.get(30, TimeUnit.SECONDS);
      long ga =
          jdbc.queryForObject(
              "SELECT COALESCE(sum(quantity),0) FROM admission_hold WHERE event_id=? AND NOT"
                  + " released AND expires_at>clock_timestamp()",
              Long.class,
              event);
      assertThat(ga + seatHolds.activeForUser(event, owner.getId())).isEqualTo(3);
    } finally {
      executor.shutdownNow();
      for (var row : rows) seatHolds.releaseHold(event, row.getId());
      jdbc.update("UPDATE admission_hold SET released=true WHERE event_id=?", event);
    }
  }

  @Test
  void publishedBulkEditCanUpdateMoreThanThreeSeatsAndRetireUnsoldPositions() throws Exception {
    var p = payload("SEATED");
    var layout = new ArrayList<Map<String, Object>>();
    for (int n = 1; n <= 8; n++) layout.add(seat("Main", n, 40 + n * 30, 150));
    p.put("seats", layout);
    var published = publish(p);
    Long event = id(published);
    var old = seats.findByVenueEventId(event);
    var update = new LinkedHashMap<String, Object>(published);
    update.put(
        "seats",
        old.subList(0, 7).stream()
            .map(
                s ->
                    new OrganizerEventRequest.SeatInput(
                        s.getId(),
                        s.getSection(),
                        s.getSeatRow(),
                        s.getSeatNumber(),
                        s.getX(),
                        s.getY() + 50,
                        new java.math.BigDecimal("825.50")))
            .toList());
    organizer.save(event, request(update));
    var saved = seats.findByVenueEventId(event);
    assertThat(saved).hasSize(7);
    assertThat(saved.stream().map(Seat::getId).toList())
        .containsExactlyElementsOf(old.subList(0, 7).stream().map(Seat::getId).toList());
    assertThat(saved)
        .allSatisfy(
            s -> {
              assertThat(s.getY()).isEqualTo(200);
              assertThat(s.getPrice()).isEqualByComparingTo("825.50");
            });
    assertThat(seats.findById(old.get(7).getId()).orElseThrow().isRetired()).isTrue();
    mvc.perform(get("/api/events/" + event + "/seats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7));
  }

  @Test
  void bookedSeatsCannotBeRemovedOrRelabelledAndStaleEditsFail() {
    var published = publish(payload("SEATED"));
    Long event = id(published);
    var old = seats.findByVenueEventId(event);
    jdbc.update(
        "INSERT INTO booking(seat_id,event_id,user_id,status) VALUES(?,?,?,'CONFIRMED')",
        old.get(0).getId(),
        event,
        owner.getId());
    var update = new LinkedHashMap<String, Object>(published);
    update.put(
        "seats",
        List.of(
            new OrganizerEventRequest.SeatInput(
                old.get(1).getId(), "Main", "A", 2, 108, 130, new java.math.BigDecimal("500"))));
    assertThatThrownBy(() -> organizer.save(event, request(update)))
        .hasMessageContaining("Booked seats cannot be deleted");
    var renamed = new ArrayList<OrganizerEventRequest.SeatInput>();
    for (var seat : old)
      renamed.add(
          new OrganizerEventRequest.SeatInput(
              seat.getId(),
              seat.getSection(),
              seat == old.get(0) ? "Z" : seat.getSeatRow(),
              seat.getSeatNumber(),
              seat.getX(),
              seat.getY(),
              seat.getPrice()));
    update.put("seats", renamed);
    assertThatThrownBy(() -> organizer.save(event, request(update)))
        .hasMessageContaining("Booked seat labels cannot change");
    var stale = new LinkedHashMap<String, Object>(published);
    stale.put("version", -1);
    assertThatThrownBy(() -> organizer.save(event, request(stale)))
        .hasMessageContaining("changed in another tab");
  }

  @Test
  void activeHoldsBlockSeatEditingButAllowReschedulingUnchangedInventory() {
    var published = publish(payload("MIXED"));
    Long event = id(published);
    var row = seats.findByVenueEventId(event).get(0);
    seatHoldForManagement(event, row.getId());
    var update = new LinkedHashMap<String, Object>(published);
    update.put("name", "Updated title");
    assertThatThrownBy(() -> organizer.save(event, request(update)))
        .hasMessageContaining("active reservations");
    update.put("startTime", LocalDateTime.now().plusDays(10).withNano(0));
    update.put("endTime", LocalDateTime.now().plusDays(10).plusHours(2).withNano(0));
    update.put("changeReason", "Venue requested a later date");
    var changed = organizer.save(event, request(update));
    assertThat(changed.get("scheduleChanged")).isEqualTo(true);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_change WHERE event_id=? AND action='RESCHEDULED'",
                Integer.class,
                event))
        .isEqualTo(1);
  }

  void seatHoldForManagement(Long event, Long seat) {
    mvcHoldHelper(event, seat);
  }

  void mvcHoldHelper(Long event, Long seat) {
    try {
      mvc.perform(
              post("/api/holds")
                  .with(jwt())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"eventId\":" + event + ",\"seatId\":" + seat + "}"))
          .andExpect(status().isOk());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void eventDeletionAndCancellationAreOwnerOnlyAndRemovePublicInventory() throws Exception {
    var draft = save(payload("SEATED"));
    Long draftId = id(draft);
    organizer.remove(draftId, ((Number) draft.get("version")).longValue(), "Draft abandoned", true);
    assertThat(events.findById(draftId).orElseThrow().getPublicationStatus()).isEqualTo("DELETED");
    assertThatThrownBy(() -> organizer.get(draftId)).hasMessageContaining("404");
    var published = publish(payload("GENERAL"));
    Long event = id(published);
    when(current.resolve(any())).thenReturn(other);
    organizer.enroll("Other organizer");
    assertThatThrownBy(
            () ->
                organizer.remove(
                    event, ((Number) published.get("version")).longValue(), "Not mine", false))
        .hasMessageContaining("404");
    when(current.resolve(any())).thenReturn(owner);
    var cancelled =
        organizer.remove(
            event, ((Number) published.get("version")).longValue(), "Venue unavailable", false);
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    mvc.perform(get("/api/events/" + event)).andExpect(status().isNotFound());
    mvc.perform(get("/api/events/" + event + "/admission")).andExpect(status().isNotFound());
    assertThatThrownBy(
            () -> organizer.publish(event, ((Number) cancelled.get("version")).longValue()))
        .hasMessageContaining("already published");
  }

  @Test
  void dateChangesRequireAReasonAndSupportMovingEarlierAndLater() {
    var published = publish(payload("SEATED"));
    Long event = id(published);
    var update = new LinkedHashMap<String, Object>(published);
    update.put("startTime", LocalDateTime.now().plusDays(3).withNano(0));
    update.put("endTime", null);
    assertThatThrownBy(() -> organizer.save(event, request(update)))
        .hasMessageContaining("Explain the date");
    update.put("changeReason", "Earlier slot at the venue");
    var earlier = organizer.save(event, request(update));
    var later = new LinkedHashMap<String, Object>(earlier);
    later.put("startTime", LocalDateTime.now().plusDays(12).withNano(0));
    later.put("changeReason", "Artist availability changed");
    var finalEvent = organizer.save(event, request(later));
    assertThat(finalEvent.get("status")).isEqualTo("PUBLISHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_change WHERE event_id=?", Integer.class, event))
        .isEqualTo(2);
  }

  @Test
  void readableSlugsAreUniqueAndRemainStableAfterRenaming() {
    var input = payload("SEATED");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    input.put("name", "Jazz & Coffee " + unique);
    var first = save(input);
    var second = save(input);
    String slug = "jazz-coffee-" + unique + "-mumbai";
    assertThat(first.get("slug")).isEqualTo(slug);
    assertThat(second.get("slug").toString()).startsWith(slug + "-").isNotEqualTo(slug);
    input.put("version", first.get("version"));
    input.put("name", "Renamed concert");
    var renamed = organizer.save(id(first), request(input));
    assertThat(renamed.get("slug")).isEqualTo(slug);
  }

  @Test
  void slugApiAndDeepLinkExposeOnlyPublishedEvents() throws Exception {
    var draft = save(payload("SEATED"));
    String slug = draft.get("slug").toString();
    mvc.perform(get("/api/events/by-slug/" + slug)).andExpect(status().isNotFound());
    mvc.perform(get("/events/" + slug)).andExpect(status().isNotFound());
    var published = organizer.publish(id(draft), ((Number) draft.get("version")).longValue());
    mvc.perform(get("/api/events/by-slug/" + slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id(draft)))
        .andExpect(jsonPath("$.slug").value(slug));
    mvc.perform(get("/events/" + slug))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
    organizer.remove(
        id(draft), ((Number) published.get("version")).longValue(), "Venue cancellation", false);
    mvc.perform(get("/api/events/by-slug/" + slug)).andExpect(status().isNotFound());
    mvc.perform(get("/events/" + slug)).andExpect(status().isNotFound());
    mvc.perform(get("/events/unknown-" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void concurrentSameNameEventsGetDifferentSlugs() throws Exception {
    var input = payload("SEATED");
    input.put("name", "Shared name " + UUID.randomUUID());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gun = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                gun.await();
                return save(input).get("slug");
              });
      var b =
          executor.submit(
              () -> {
                gun.await();
                return save(input).get("slug");
              });
      gun.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS)).isNotEqualTo(b.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void richInformationRoundTripsAndOlderEditorsPreserveIt() throws Exception {
    var p = payload("SEATED");
    p.put(
        "information",
        Map.of(
            "languages",
            "Hindi, English",
            "artists",
            "Independent performers",
            "entryInstructions",
            "Bring ID",
            "ticketInclusions",
            "Admission and a seat",
            "accessibilityInfo",
            "Step-free entrance on Park Road",
            "faq",
            "Can I bring a bag? Small bags only."));
    p.put("cancellationPolicy", Map.of("enabled", true, "cutoffHours", 24));
    var draft = organizer.save(null, request(p));
    long id = id(draft);
    var saved = events.findById(id).orElseThrow();
    assertThat(saved.getLanguages()).isEqualTo("Hindi, English");
    assertThat(saved.getCancellationCutoffHours()).isEqualTo(24);
    var update = payload("SEATED");
    update.put("version", draft.get("version"));
    organizer.save(id, request(update));
    assertThat(events.findById(id).orElseThrow().getTicketInclusions())
        .isEqualTo("Admission and a seat");
    var currentDraft = organizer.get(id);
    organizer.publish(id, ((Number) currentDraft.get("version")).longValue());
    mvc.perform(get("/api/events/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.information.languages").value("Hindi, English"))
        .andExpect(jsonPath("$.organizerName").value("Independent Organizer"))
        .andExpect(jsonPath("$.cancellationCutoffHours").value(24));
    mvc.perform(get("/events/" + events.findById(id).orElseThrow().getSlug() + "/book"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void eventInformationCannotExceedItsBoundedFields() throws Exception {
    var p = payload("SEATED");
    p.put("information", Map.of("languages", "x".repeat(161)));
    mvc.perform(
            post("/api/organizer/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(p)))
        .andExpect(status().isBadRequest());
  }
}
