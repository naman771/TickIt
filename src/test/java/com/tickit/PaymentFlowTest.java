package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.model.*;
import com.tickit.payment.*;
import com.tickit.repository.*;
import com.tickit.service.SeatHoldService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest(
    properties = {
      "app.auth.mode=catalog",
      "app.payments.enabled=true",
      "app.payments.key-id=rzp_test_fixture",
      "app.payments.key-secret=fixture-secret",
      "app.payments.webhook-secret=fixture-webhook-secret-at-least-24",
      "app.payments.reconciliation-enabled=false",
      "app.payments.refunds-enabled=false",
      "app.auth.developer-subjects=payment_test_developer"
    })
@AutoConfigureMockMvc
class PaymentFlowTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired EventRepository events;
  @Autowired OrganizerAccountRepository organizerAccounts;
  @Autowired VenueRepository venues;
  @Autowired SeatRepository seats;
  @Autowired UserRepository users;
  @Autowired BookingRepository bookings;
  @Autowired SeatHoldService holds;
  @Autowired AdmissionPoolRepository pools;
  @Autowired AdmissionTicketTypeRepository types;
  @Autowired CheckoutService service;
  @Autowired com.tickit.payment.RefundService refunds;
  @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @MockitoBean RazorpayGateway gateway;
  Event event;
  AppUser user, other;
  List<Seat> rows;
  AdmissionPool pool;
  AdmissionTicketType type;
  static final String ISSUER = "https://payments.tickit.test";

  @BeforeEach
  void fixture() {
    redis.delete("rl:ip:127.0.0.1");
    event =
        events.saveAndFlush(
            Event.builder()
                .name("Payment regression")
                .guestCapacity(20)
                .city("Mumbai")
                .currency("INR")
                .admissionMode("MIXED")
                .minimumAge(18)
                .startTime(LocalDateTime.now().plusDays(30))
                .build());
    var venue =
        venues.saveAndFlush(
            Venue.builder().event(event).name("Payment hall").location("12 Park Road").build());
    rows = new ArrayList<>();
    for (int n = 1; n <= 4; n++)
      rows.add(
          seats.saveAndFlush(
              Seat.builder()
                  .venue(venue)
                  .seatRow("P")
                  .seatNumber(n)
                  .x(40 + n * 30)
                  .y(160)
                  .price(new BigDecimal("123.45"))
                  .build()));
    pool = new AdmissionPool();
    pool.setEvent(event);
    pool.setName("Floor");
    pool.setCapacity(3);
    pool = pools.saveAndFlush(pool);
    type = new AdmissionTicketType();
    type.setPool(pool);
    type.setName("Regular");
    type.setPrice(new BigDecimal("200.00"));
    type.setQuota(3);
    type = types.saveAndFlush(type);
    user = account();
    other = account();
    reset(gateway);
    when(gateway.create(any(), anyLong(), anyString()))
        .thenAnswer(
            a ->
                new RazorpayGateway.Order(
                    "order_" + a.getArgument(0).toString().replace("-", ""),
                    a.getArgument(1),
                    a.getArgument(2),
                    a.getArgument(0).toString()));
  }

  AppUser account() {
    String subject = UUID.randomUUID().toString();
    return users.saveAndFlush(
        AppUser.builder()
            .fullName("Payment test")
            .email(subject + "@tickit.test")
            .authIssuer(ISSUER)
            .authSubject(subject)
            .build());
  }

  @AfterEach
  void cleanup() {
    for (var row : rows) holds.releaseHold(event.getId(), row.getId());
    jdbc.update("DELETE FROM booking WHERE event_id=?", event.getId());
    jdbc.update(
        "DELETE FROM checkout_item WHERE order_id IN (SELECT id FROM checkout_order WHERE"
            + " event_id=?)",
        event.getId());
    jdbc.update("DELETE FROM checkout_order WHERE event_id=?", event.getId());
    jdbc.update("DELETE FROM admission_hold WHERE event_id=?", event.getId());
    jdbc.update("DELETE FROM admission_ticket_type WHERE pool_id=?", pool.getId());
    jdbc.update("DELETE FROM admission_pool WHERE id=?", pool.getId());
    jdbc.update("DELETE FROM seat WHERE venue_id=?", rows.getFirst().getVenue().getId());
    jdbc.update("DELETE FROM venue WHERE event_id=?", event.getId());
    jdbc.update("DELETE FROM event_change WHERE event_id=?", event.getId());
    events.deleteById(event.getId());
    jdbc.update("DELETE FROM organizer_account WHERE user_id=?", user.getId());
    users.deleteById(user.getId());
    users.deleteById(other.getId());
  }

  Map<String, Object> request(List<Long> ids, Long type, int quantity) {
    var body = new LinkedHashMap<String, Object>();
    body.put("eventId", event.getId());
    body.put("eventVersion", events.findById(event.getId()).orElseThrow().getLayoutVersion());
    body.put("idempotencyKey", UUID.randomUUID());
    body.put("seatIds", ids);
    body.put("ticketTypeId", type);
    body.put("quantity", quantity);
    body.put("attendeeAge", 25);
    return body;
  }

  List<Long> selected(int n) {
    return rows.stream().limit(n).map(Seat::getId).toList();
  }

  org.springframework.test.web.servlet.request.RequestPostProcessor as(AppUser who) {
    return jwt().jwt(j -> j.issuer(ISSUER).subject(who.getAuthSubject()));
  }

  JsonNode create(Map<String, Object> body) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/api/payments/orders")
                    .with(as(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  RazorpayGateway.Payment payment(JsonNode order, String status) {
    var p =
        new RazorpayGateway.Payment(
            "pay_fixture",
            order.get("providerOrderId").asString(),
            order.get("amount").asLong(),
            "INR",
            status);
    when(gateway.payment(p.id())).thenReturn(p);
    when(gateway.order(p.orderId()))
        .thenReturn(
            new RazorpayGateway.Order(
                p.orderId(), p.amount(), p.currency(), order.get("id").asString()));
    when(gateway.payments(p.orderId())).thenReturn(List.of(p));
    return p;
  }

  String hmac(byte[] body, String secret) throws Exception {
    var m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(m.doFinal(body));
  }

  JsonNode verify(JsonNode order) throws Exception {
    String oid = order.get("providerOrderId").asString();
    var body =
        Map.of(
            "razorpay_order_id",
            oid,
            "razorpay_payment_id",
            "pay_fixture",
            "razorpay_signature",
            hmac((oid + "|pay_fixture").getBytes(StandardCharsets.UTF_8), "fixture-secret"));
    return json.readTree(
        mvc.perform(
                post("/api/payments/orders/" + order.get("id").asString() + "/verify")
                    .with(as(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  byte[] webhook() {
    return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_fixture\"}}}}"
        .getBytes(StandardCharsets.UTF_8);
  }

  void deliver(byte[] body) throws Exception {
    mvc.perform(
            post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Razorpay-Signature", hmac(body, "fixture-webhook-secret-at-least-24")))
        .andExpect(status().isOk());
  }

  @Test
  void unauthenticatedCheckoutAndHistoryAreRejected() throws Exception {
    mvc.perform(post("/api/payments/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/payments/orders/me")).andExpect(status().isUnauthorized());
    verifyNoInteractions(gateway);
  }

  @Test
  void threeSeatsAreServerPricedAndOnlyConfirmedAfterCapture() throws Exception {
    var order = create(request(selected(3), null, 0));
    assertEquals(37035, order.get("amount").asLong());
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    payment(order, "captured");
    assertEquals("CONFIRMED", verify(order).get("status").asString());
    assertEquals(3, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    for (var row : rows.subList(0, 3)) assertFalse(holds.isSeatHeld(event.getId(), row.getId()));
  }

  @Test
  void repeatedRequestReturnsSameOrderAndDifferentPayloadCannotReuseKey() throws Exception {
    var req = request(selected(1), null, 0);
    var first = create(req);
    assertEquals(first.get("id"), create(req).get("id"));
    org.mockito.Mockito.verify(gateway, times(1)).create(any(), anyLong(), anyString());
    req.put("seatIds", selected(2));
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void onlyOneActiveOrderPerAccountAndEvent() throws Exception {
    create(request(selected(1), null, 0));
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(List.of(rows.get(1).getId()), null, 0))))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidAgeCurrencyQuantityDuplicatesAndCrossEventNeverReachGateway() throws Exception {
    var req = request(selected(1), null, 0);
    req.put("attendeeAge", 17);
    for (var body :
        List.of(
            req,
            request(selected(4), null, 0),
            request(List.of(rows.get(0).getId(), rows.get(0).getId()), null, 0),
            request(List.of(), null, 0),
            request(selected(1), type.getId(), 0),
            request(List.of(9999999L), null, 0)))
      mvc.perform(
              post("/api/payments/orders")
                  .with(as(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(body)))
          .andExpect(status().is4xxClientError());
    event.setCurrency("USD");
    events.saveAndFlush(event);
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(selected(1), null, 0))))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(gateway);
  }

  @Test
  void partialSeatAcquisitionRollsBackAndReleasesOnlyNewHolds() throws Exception {
    holds.acquireHold(event.getId(), rows.get(1).getId(), other.getId());
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(selected(2), null, 0))))
        .andExpect(status().isConflict());
    assertFalse(holds.isSeatHeld(event.getId(), rows.get(0).getId()));
    assertTrue(holds.isHoldValid(event.getId(), rows.get(1).getId(), other.getId()));
    verifyNoInteractions(gateway);
  }

  @Test
  void badSignatureDoesNotFetchOrConfirmPayment() throws Exception {
    var order = create(request(selected(1), null, 0));
    clearInvocations(gateway);
    var body =
        Map.of(
            "razorpay_order_id",
            order.get("providerOrderId").asString(),
            "razorpay_payment_id",
            "pay_fixture",
            "razorpay_signature",
            "0".repeat(64));
    mvc.perform(
            post("/api/payments/orders/" + order.get("id").asString() + "/verify")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(gateway);
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void authorizedPaymentAloneDoesNotConfirm() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "authorized");
    assertEquals("PENDING", verify(order).get("status").asString());
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void mismatchedAmountCurrencyAndOrderAreRejected() throws Exception {
    var order = create(request(selected(1), null, 0));
    for (var p :
        List.of(
            new RazorpayGateway.Payment(
                "pay_fixture", order.get("providerOrderId").asString(), 1, "INR", "captured"),
            new RazorpayGateway.Payment(
                "pay_fixture",
                order.get("providerOrderId").asString(),
                order.get("amount").asLong(),
                "USD",
                "captured"),
            new RazorpayGateway.Payment(
                "pay_fixture", "order_another", order.get("amount").asLong(), "INR", "captured"))) {
      when(gateway.payment("pay_fixture")).thenReturn(p);
      mvc.perform(
              post("/api/payments/orders/" + order.get("id").asString() + "/reconcile")
                  .with(as(user)))
          .andExpect(status().isOk());
      when(gateway.payments(order.get("providerOrderId").asString())).thenReturn(List.of(p));
      mvc.perform(
              post("/api/payments/orders/" + order.get("id").asString() + "/reconcile")
                  .with(as(user)))
          .andExpect(status().isConflict());
      when(gateway.payments(order.get("providerOrderId").asString())).thenReturn(List.of());
    }
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void anotherAccountCannotReadVerifyReconcileOrCancelOrder() throws Exception {
    var order = create(request(selected(1), null, 0));
    String url = "/api/payments/orders/" + order.get("id").asString();
    mvc.perform(get(url).with(as(other))).andExpect(status().isNotFound());
    mvc.perform(delete(url).with(as(other))).andExpect(status().isNotFound());
    mvc.perform(post(url + "/reconcile").with(as(other))).andExpect(status().isNotFound());
  }

  @Test
  void webhookSignatureIsRawBodyBoundAndPublicEndpointWorksWithoutLogin() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    var body = webhook();
    mvc.perform(
            post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new String(body, StandardCharsets.UTF_8) + " ")
                .header("X-Razorpay-Signature", hmac(body, "fixture-webhook-secret-at-least-24")))
        .andExpect(status().isUnauthorized());
    deliver(body);
    assertEquals(1, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void callbackAndDuplicateWebhookAreIdempotent() throws Exception {
    var order = create(request(selected(2), null, 0));
    payment(order, "captured");
    verify(order);
    deliver(webhook());
    deliver(webhook());
    verify(order);
    assertEquals(2, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void concurrentWebhooksCreateExactlyOneBookingPerSeat() throws Exception {
    var order = create(request(selected(3), null, 0));
    payment(order, "captured");
    var body = webhook();
    var signature = hmac(body, "fixture-webhook-secret-at-least-24");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++)
        calls.add(executor.submit(() -> service.webhook(body, signature)));
      for (var f : calls) f.get(10, TimeUnit.SECONDS);
    }
    assertEquals(3, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void expiredCaptureIsRecordedForReviewWithoutBooking() throws Exception {
    var order = create(request(selected(2), null, 0));
    payment(order, "captured");
    jdbc.update(
        "UPDATE checkout_order SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        UUID.fromString(order.get("id").asString()));
    assertEquals("REVIEW_REQUIRED", verify(order).get("status").asString());
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void missingOneSeatNeverCreatesPartialMultiSeatBooking() throws Exception {
    var order = create(request(selected(3), null, 0));
    payment(order, "captured");
    holds.releaseHold(event.getId(), rows.get(1).getId());
    assertEquals("REVIEW_REQUIRED", verify(order).get("status").asString());
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void mixedCheckoutConvertsStandingAndSeatsAtomicallyAndCountsAccountLimit() throws Exception {
    var order = create(request(selected(1), type.getId(), 2));
    assertEquals(52345, order.get("amount").asLong());
    payment(order, "captured");
    verify(order);
    assertEquals(1, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    assertEquals(3, bookings.countAllTickets(user.getId(), event.getId()));
    assertEquals(2, pools.findById(pool.getId()).orElseThrow().getSold());
    assertEquals(2, types.findById(type.getId()).orElseThrow().getSold());
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(List.of(rows.get(1).getId()), null, 0))))
        .andExpect(status().isConflict());
  }

  @Test
  void standingOnlyCheckoutRetainsConfirmedOrderLines() throws Exception {
    var order = create(request(List.of(), type.getId(), 3));
    payment(order, "captured");
    assertEquals("CONFIRMED", verify(order).get("status").asString());
    mvc.perform(get("/api/payments/orders/me").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].items[0].quantity").value(3));
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    assertEquals(3, bookings.countAllTickets(user.getId(), event.getId()));
  }

  @Test
  void reconciliationRecoversLostBrowserCallback() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    mvc.perform(
            post("/api/payments/orders/" + order.get("id").asString() + "/reconcile")
                .with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void uncertainOrderCreationIsNotRetriedAtProvider() throws Exception {
    doThrow(new IllegalStateException("Timeout"))
        .when(gateway)
        .create(any(), anyLong(), anyString());
    var req = request(selected(1), null, 0);
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
        .andExpect(status().isServiceUnavailable());
    assertEquals("CREATE_UNKNOWN", create(req).get("status").asString());
    org.mockito.Mockito.verify(gateway, times(1)).create(any(), anyLong(), anyString());
  }

  @Test
  void paymentReviewQueueIsDeveloperOnlyAndRetainsCapturedReference() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    holds.releaseHold(event.getId(), rows.get(0).getId());
    verify(order);
    mvc.perform(get("/api/developer/payments/review")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/developer/payments/review").with(as(user)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/developer/payments/review")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("REVIEW_REQUIRED"))
        .andExpect(jsonPath("$[0].provider_payment_id").value("pay_fixture"));
  }

  @Test
  void rollbackCannotLeavePartialPaidInventoryOrReleaseReservations() throws Exception {
    var order = create(request(selected(1), type.getId(), 2));
    payment(order, "captured");
    var body = webhook();
    String signature = hmac(body, "fixture-webhook-secret-at-least-24");
    transaction.executeWithoutResult(
        status -> {
          service.webhook(body, signature);
          status.setRollbackOnly();
        });
    assertEquals(0, bookings.countAllTickets(user.getId(), event.getId()));
    assertEquals(0, pools.findById(pool.getId()).orElseThrow().getSold());
    assertTrue(
        holds.isCheckoutHoldValid(
            event.getId(),
            rows.get(0).getId(),
            user.getId(),
            UUID.fromString(order.get("id").asString())));
    deliver(body);
    assertEquals(3, bookings.countAllTickets(user.getId(), event.getId()));
  }

  @Test
  void signedWebhookRecoversProviderOrderCreatedBeforeNetworkTimeout() throws Exception {
    doThrow(new IllegalStateException("Timeout"))
        .when(gateway)
        .create(any(), anyLong(), anyString());
    var req = request(selected(1), null, 0);
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
        .andExpect(status().isServiceUnavailable());
    var order = create(req);
    when(gateway.payment("pay_fixture"))
        .thenReturn(
            new RazorpayGateway.Payment(
                "pay_fixture", "order_recovered", 12345, "INR", "captured"));
    when(gateway.order("order_recovered"))
        .thenReturn(
            new RazorpayGateway.Order("order_recovered", 12345, "INR", order.get("id").asString()));
    deliver(webhook());
    assertEquals(1, bookings.countAllTickets(user.getId(), event.getId()));
  }

  @Test
  void clientSuppliedPriceCannotChangeTheCharge() throws Exception {
    var req = request(selected(1), null, 0);
    req.put("amount", 1);
    req.put("currency", "USD");
    var order = create(req);
    assertEquals(12345, order.get("amount").asLong());
    assertEquals("INR", order.get("currency").asString());
  }

  @Test
  void cancelThenLateOldPaymentCannotReleaseANewerSameAccountHold() throws Exception {
    var first = create(request(selected(1), null, 0));
    payment(first, "captured");
    mvc.perform(delete("/api/payments/orders/" + first.get("id").asString()).with(as(user)))
        .andExpect(status().isOk());
    var next = create(request(selected(1), null, 0));
    assertTrue(
        holds.isCheckoutHoldValid(
            event.getId(),
            rows.get(0).getId(),
            user.getId(),
            UUID.fromString(next.get("id").asString())));
    assertEquals("REVIEW_REQUIRED", verify(first).get("status").asString());
    assertTrue(
        holds.isCheckoutHoldValid(
            event.getId(),
            rows.get(0).getId(),
            user.getId(),
            UUID.fromString(next.get("id").asString())));
  }

  @Test
  void expiredStandingCapacityCannotBeOversoldByLateCapture() throws Exception {
    var order = create(request(List.of(), type.getId(), 3));
    payment(order, "captured");
    jdbc.update("UPDATE admission_hold SET released=true WHERE event_id=?", event.getId());
    assertEquals("REVIEW_REQUIRED", verify(order).get("status").asString());
    assertEquals(0, pools.findById(pool.getId()).orElseThrow().getSold());
  }

  void organizerOwnsFixture() throws Exception {
    mvc.perform(
            post("/api/organizer/profile")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Payment organizer\"}"))
        .andExpect(status().isOk());
    event.setOrganizer(organizerAccounts.findById(user.getId()).orElseThrow());
    event = events.saveAndFlush(event);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> eventForEditing() throws Exception {
    return json.readValue(
        mvc.perform(get("/api/organizer/events/" + event.getId()).with(as(user)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        Map.class);
  }

  @Test
  void cancellationExpiresPendingCheckoutAndLateCaptureCannotBookCancelledEvent() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(2), null, 0));
    payment(order, "captured");
    mvc.perform(
            post("/api/organizer/events/" + event.getId() + "/cancel")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            events.findById(event.getId()).orElseThrow().getLayoutVersion(),
                            "reason",
                            "Venue cancelled"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertFalse(holds.isSeatHeld(event.getId(), rows.get(0).getId()));
    assertEquals("REVIEW_REQUIRED", verify(order).get("status").asString());
    assertEquals(0, bookings.countAllTickets(user.getId(), event.getId()));
  }

  @Test
  void cancelledOrDeletedPaidEventsKeepReceiptAndBookedSeatHistory() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    verify(order);
    mvc.perform(
            post("/api/organizer/events/" + event.getId() + "/cancel")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            events.findById(event.getId()).orElseThrow().getLayoutVersion(),
                            "reason",
                            "Show cancelled"))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/bookings/me").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].event.status").value("CANCELLED"));
    mvc.perform(
            delete("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            events.findById(event.getId()).orElseThrow().getLayoutVersion(),
                            "reason",
                            "Archive cancelled event"))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/payments/orders/me").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$[0].event.status").value("DELETED"));
    assertEquals(1, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    mvc.perform(get("/api/events/" + event.getId())).andExpect(status().isNotFound());
  }

  @Test
  void rescheduleExpiresOldCheckoutAndRejectsStaleCustomerSelection() throws Exception {
    organizerOwnsFixture();
    var req = request(selected(1), null, 0);
    var order = create(req);
    payment(order, "captured");
    var change = eventForEditing();
    change.put("startTime", LocalDateTime.now().plusDays(40).withNano(0));
    change.put("changeReason", "Later artist date");
    mvc.perform(
            put("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(change)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scheduleChanged").value(true));
    assertEquals("REVIEW_REQUIRED", verify(order).get("status").asString());
    assertEquals(0, bookings.countAllTickets(user.getId(), event.getId()));
    req.put("idempotencyKey", UUID.randomUUID());
    mvc.perform(
            post("/api/payments/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
        .andExpect(status().isConflict());
    var fresh = create(request(selected(1), null, 0));
    assertEquals("PENDING", fresh.get("status").asString());
  }

  @Test
  void retiredSeatReferencesRemainValidForExpiredOrderHistory() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(1), null, 0));
    mvc.perform(delete("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk());
    var update = eventForEditing();
    var inputs = json.convertValue(update.get("seats"), java.util.List.class);
    inputs.remove(0);
    update.put("seats", inputs);
    mvc.perform(
            put("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(update)))
        .andExpect(status().isOk());
    assertTrue(seats.findById(rows.get(0).getId()).orElseThrow().isRetired());
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].seatId").value(rows.get(0).getId()));
  }

  UUID refundOrderId(JsonNode order) {
    return UUID.fromString(order.get("id").asString());
  }

  RazorpayGateway.Refund refundResponse(JsonNode order, String status) {
    return new RazorpayGateway.Refund(
        "rfnd_fixture",
        "pay_fixture",
        order.get("amount").asLong(),
        "INR",
        status,
        order.get("id").asString());
  }

  JsonNode paidForRefund() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    verify(order);
    return order;
  }

  void cancelForRefund() throws Exception {
    mvc.perform(
            post("/api/organizer/events/" + event.getId() + "/cancel")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            events.findById(event.getId()).orElseThrow().getLayoutVersion(),
                            "reason",
                            "Venue unavailable"))))
        .andExpect(status().isOk());
  }

  void refundDue(JsonNode order) {
    jdbc.update(
        "UPDATE refund_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE"
            + " order_id=?",
        refundOrderId(order));
  }

  @Test
  void cancellationAutomaticallyQueuesOneFullRefundAndPreservesReceipt() throws Exception {
    var order = paidForRefund();
    refunds.enqueueEligible(event.getId());
    assertNull(refunds.summary(refundOrderId(order)));
    cancelForRefund();
    refunds.enqueueEligible(event.getId());
    assertEquals("QUEUED", refunds.summary(refundOrderId(order)).status());
    when(gateway.createRefund("pay_fixture", order.get("amount").asLong(), refundOrderId(order)))
        .thenReturn(refundResponse(order, "processed"));
    assertTrue(refunds.processOne());
    assertFalse(refunds.processOne());
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, times(1))
        .createRefund("pay_fixture", order.get("amount").asLong(), refundOrderId(order));
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refund.status").value("PROCESSED"))
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    assertEquals(1, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
    mvc.perform(get("/api/developer/payments/refunds")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/developer/payments/refunds").with(as(user)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/developer/payments/refunds")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("PROCESSED"));
  }

  @Test
  void verifiedCaptureWithoutTicketsIsRefundedAutomatically() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    holds.releaseHold(event.getId(), rows.getFirst().getId());
    verify(order);
    assertEquals("TICKETS_NOT_ISSUED", refunds.summary(refundOrderId(order)).reason());
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenReturn(refundResponse(order, "processed"));
    refunds.processOne();
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    assertEquals(0, bookings.countByUserIdAndEventId(user.getId(), event.getId()));
  }

  @Test
  void uncertainRefundRetryUsesSameKeyAndPendingRefundIsPolledWithoutAnotherPost()
      throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new IllegalStateException("Timeout after provider acceptance"))
        .thenReturn(refundResponse(order, "pending"));
    refunds.processOne();
    assertEquals("RETRY", refunds.summary(refundOrderId(order)).status());
    refundDue(order);
    refunds.processOne();
    assertEquals("PENDING", refunds.summary(refundOrderId(order)).status());
    when(gateway.refund("rfnd_fixture")).thenReturn(refundResponse(order, "processed"));
    refundDue(order);
    refunds.processOne();
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, times(2))
        .createRefund("pay_fixture", order.get("amount").asLong(), refundOrderId(order));
    org.mockito.Mockito.verify(gateway, times(1)).refund("rfnd_fixture");
  }

  @Test
  void mismatchedPaymentNeverTriggersRefund() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.payment("pay_fixture"))
        .thenReturn(
            new RazorpayGateway.Payment("pay_fixture", "order_unrelated", 100, "INR", "captured"));
    refunds.processOne();
    assertEquals("REVIEW", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void mismatchedRefundResponseRequiresReviewRatherThanClaimingCompletion() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenReturn(
            new RazorpayGateway.Refund(
                "rfnd_bad", "pay_other", 100, "INR", "processed", order.get("id").asString()));
    refunds.processOne();
    assertEquals("REVIEW", refunds.summary(refundOrderId(order)).status());
  }

  @Test
  void signedRefundWebhookRefetchesProviderAndCannotRegressProcessedStatus() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.refund("rfnd_fixture")).thenReturn(refundResponse(order, "processed"));
    byte[] body =
        "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_fixture\",\"amount\":1}}}}"
            .getBytes(StandardCharsets.UTF_8);
    mvc.perform(
            post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Razorpay-Signature", "bad"))
        .andExpect(status().isUnauthorized());
    org.mockito.Mockito.verify(gateway, never()).refund(anyString());
    deliver(body);
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    when(gateway.refund("rfnd_fixture")).thenReturn(refundResponse(order, "pending"));
    deliver(body);
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void failedBankRefundAndExhaustedTransportRetriesAreVisibleForFollowUp() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new IllegalStateException("provider unavailable"));
    jdbc.update("UPDATE refund_job SET failures=11 WHERE order_id=?", refundOrderId(order));
    refunds.processOne();
    assertEquals("FAILED", refunds.summary(refundOrderId(order)).status());
    assertFalse(refunds.processOne());
  }

  @Test
  void providerFailedRefundIsTerminalAndNotResubmitted() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenReturn(refundResponse(order, "failed"));
    refunds.processOne();
    assertEquals("FAILED", refunds.summary(refundOrderId(order)).status());
    assertFalse(refunds.processOne());
  }

  @Test
  void competingRefundWorkersClaimOneJobAndExpiredLeaseCanRecoverIt() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    jdbc.update(
        "UPDATE refund_job SET lease_token=?,lease_until=clock_timestamp()-interval '1 second'"
            + " WHERE order_id=?",
        UUID.randomUUID(),
        refundOrderId(order));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return refundResponse(order, "processed");
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> refunds.processOne());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertFalse(refunds.processOne());
      } finally {
        release.countDown();
      }
      assertTrue(first.get(5, TimeUnit.SECONDS));
    }
    org.mockito.Mockito.verify(gateway, times(1)).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void rollbackDoesNotLeaveRefundWorkOrCallTheProvider() throws Exception {
    var order = paidForRefund();
    transaction.executeWithoutResult(
        tx -> {
          jdbc.update("UPDATE event SET publication_status='CANCELLED' WHERE id=?", event.getId());
          refunds.enqueueEligible(event.getId());
          tx.setRollbackOnly();
        });
    assertNull(refunds.summary(refundOrderId(order)));
    assertFalse(refunds.processOne());
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void verifiedExternalFullRefundIsRecognizedAndPartialRefundRequiresReview() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.payment("pay_fixture"))
        .thenReturn(
            new RazorpayGateway.Payment(
                "pay_fixture",
                order.get("providerOrderId").asString(),
                order.get("amount").asLong(),
                "INR",
                "captured",
                50));
    refunds.processOne();
    assertEquals("REVIEW", refunds.summary(refundOrderId(order)).status());
    jdbc.update("UPDATE refund_job SET status='QUEUED' WHERE order_id=?", refundOrderId(order));
    when(gateway.payment("pay_fixture"))
        .thenReturn(
            new RazorpayGateway.Payment(
                "pay_fixture",
                order.get("providerOrderId").asString(),
                order.get("amount").asLong(),
                "INR",
                "refunded",
                order.get("amount").asLong()));
    refunds.processOne();
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void deletingPaidEventQueuesFullRefundOnceWithoutErasingHistory() throws Exception {
    var order = paidForRefund();
    mvc.perform(
            delete("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "version",
                            events.findById(event.getId()).orElseThrow().getLayoutVersion(),
                            "reason",
                            "Event withdrawn"))))
        .andExpect(status().isOk());
    refunds.enqueueEligible(event.getId());
    assertEquals("QUEUED", refunds.summary(refundOrderId(order)).status());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM refund_job WHERE order_id=?",
            Integer.class,
            refundOrderId(order)));
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.event.status").value("DELETED"));
  }

  @Test
  void reschedulingConfirmedTicketsDoesNotTriggerRefund() throws Exception {
    var order = paidForRefund();
    var change = eventForEditing();
    change.put("startTime", LocalDateTime.now().plusDays(40).withNano(0));
    change.put("changeReason", "Artist date moved");
    mvc.perform(
            put("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(change)))
        .andExpect(status().isOk());
    refunds.enqueueEligible(event.getId());
    assertNull(refunds.summary(refundOrderId(order)));
    assertFalse(refunds.processOne());
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }
}
