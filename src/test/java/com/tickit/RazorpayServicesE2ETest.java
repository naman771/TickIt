package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.model.*;
import com.tickit.payment.RazorpayGateway;
import com.tickit.repository.*;
import com.tickit.service.CurrentUserService;
import com.tickit.service.SeatHoldService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Real test-account order creation, with disposable database fixtures and no payment initiation.
 */
@EnabledIfEnvironmentVariable(named = "RAZORPAY_E2E", matches = "true")
@ActiveProfiles("razorpay-e2e")
@SpringBootTest(
    properties = {
      "app.auth.mode=catalog",
      "app.seed.enabled=false",
      "app.storage.enabled=false",
      "app.payments.enabled=true",
      "app.payments.reconciliation-enabled=false",
      "app.payments.refunds-enabled=false",
      "spring.datasource.hikari.maximum-pool-size=3",
      "spring.datasource.hikari.minimum-idle=0"
    })
@AutoConfigureMockMvc
class RazorpayServicesE2ETest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired EventRepository events;
  @Autowired VenueRepository venues;
  @Autowired SeatRepository seats;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired SeatHoldService holds;
  @Autowired RazorpayGateway gateway;
  @MockitoBean CurrentUserService current;

  @Test
  void realTestOrderMatchesServerPriceAndRemainsUnpaid() throws Exception {
    String expected = System.getenv("RAZORPAY_E2E_DATABASE_HOST");
    assertNotNull(expected, "Explicit disposable database hostname is required");
    assertFalse(
        expected.contains("ep-summer-surf-bavbzhxi"), "Never write these fixtures to production");
    try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      assertTrue(
          connection.getMetaData().getURL().contains(expected), "Disposable database mismatch");
    }
    Event event = null;
    Venue venue = null;
    Seat seat = null;
    AppUser user = null;
    UUID orderId = null;
    try {
      event =
          events.saveAndFlush(
              Event.builder()
                  .name("Isolated Razorpay provider verification")
                  .startTime(java.time.LocalDateTime.now().plusDays(30))
                  .currency("INR")
                  .build());
      venue =
          venues.saveAndFlush(Venue.builder().name("Isolated provider check").event(event).build());
      seat =
          seats.saveAndFlush(
              Seat.builder().venue(venue).seatRow("T").seatNumber(1).price(BigDecimal.ONE).build());
      user =
          users.saveAndFlush(
              AppUser.builder()
                  .email(UUID.randomUUID() + "@tickit.test")
                  .fullName("Isolated provider verification")
                  .build());
      when(current.resolve(null)).thenReturn(user);
      var body =
          Map.of(
              "eventId",
              event.getId(),
              "eventVersion",
              event.getLayoutVersion(),
              "idempotencyKey",
              UUID.randomUUID(),
              "seatIds",
              List.of(seat.getId()),
              "quantity",
              0);
      var response =
          mvc.perform(
                  post("/api/payments/orders")
                      .with(jwt())
                      .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                      .content(json.writeValueAsString(body)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.status").value("PENDING"))
              .andExpect(jsonPath("$.amount").value(100))
              .andExpect(jsonPath("$.currency").value("INR"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      var order = json.readTree(response);
      orderId = UUID.fromString(order.path("id").asString());
      String providerId = order.path("providerOrderId").asString();
      var actual = gateway.order(providerId);
      assertEquals(orderId.toString(), actual.receipt());
      assertEquals(100, actual.amount());
      assertEquals("INR", actual.currency());
      assertTrue(
          gateway.payments(providerId).stream().noneMatch(p -> "captured".equals(p.status())));
      assertTrue(holds.isCheckoutHoldValid(event.getId(), seat.getId(), user.getId(), orderId));
      mvc.perform(delete("/api/payments/orders/" + orderId).with(jwt()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("EXPIRED"));
      assertFalse(holds.isSeatHeld(event.getId(), seat.getId()));
      System.out.println(
          "RAZORPAY_E2E: real provider order create/fetch PASS; INR 100 paise and receipt match"
              + " PASS; no captured payment PASS; local hold release PASS");
    } finally {
      if (event != null) {
        if (seat != null) holds.releaseHold(event.getId(), seat.getId());
        jdbc.update(
            "DELETE FROM checkout_item WHERE order_id IN (SELECT id FROM checkout_order WHERE"
                + " event_id=?)",
            event.getId());
        jdbc.update("DELETE FROM checkout_order WHERE event_id=?", event.getId());
        jdbc.update("DELETE FROM booking WHERE event_id=?", event.getId());
        if (seat != null) seats.deleteById(seat.getId());
        if (venue != null) venues.deleteById(venue.getId());
        events.deleteById(event.getId());
      }
      if (user != null) users.deleteById(user.getId());
    }
  }
}
