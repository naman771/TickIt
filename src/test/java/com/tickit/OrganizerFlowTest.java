package com.tickit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

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
  @MockBean CurrentUserService current;
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
    d.put("perOrderLimit", 10);
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
    assertThat(publicEvents.findValuesAsText("id")).doesNotContain(id.toString());
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
  void publishedLayoutIsImmutableAndOtherEventIsIndependent() {
    var first = publish(payload("SEATED"));
    var p = payload("SEATED");
    p.put("version", first.get("version"));
    assertThatThrownBy(() -> organizer.save(id(first), request(p)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
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
                  admission.hold(event, tier.get(n % 2).getId(), 1);
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
    var held = admission.hold(event, tier.get(0).getId(), 3);
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
    assertThatThrownBy(() -> admission.hold(event, tier, 3))
        .hasMessageContaining("per-order limit");
    var held = admission.hold(event, tier, 2);
    assertThatThrownBy(() -> admission.hold(event, tier, 1)).hasMessageContaining("per-account");
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
    assertThatThrownBy(() -> admission.hold(future, futureType, 1))
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
}
