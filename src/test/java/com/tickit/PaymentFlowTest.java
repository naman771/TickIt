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
      "app.tickets.recovery-enabled=false",
      "app.notifications.scheduled-enabled=false",
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
  @Autowired com.tickit.ticket.TicketService ticketService;
  @Autowired com.tickit.notification.NotificationOutbox outbox;
  @Autowired com.tickit.notification.NotificationWorker notificationWorker;
  @Autowired com.tickit.notification.EmailSettings emailSettings;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.tickit.ticket.TicketAccess ticketAccess;

  @MockitoBean com.tickit.notification.MailTransport mail;

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
    reset(gateway, mail);
    emailSettings.setAllowedRecipients("");
    org.springframework.test.util.ReflectionTestUtils.setField(
        notificationWorker, "enabled", false);
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
  void removingCancelledEventTicketsIsPrivateIdempotentAndPreservesRefunds() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    var confirmed = verify(order);
    String ticket = confirmed.get("tickets").get(0).get("id").asString();
    bookings.saveAndFlush(
        Booking.builder().event(event).seat(rows.get(1)).user(other).status("CONFIRMED").build());
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
                            "Venue cancellation"))))
        .andExpect(status().isOk());
    String refundBefore =
        jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM refund_job r WHERE order_id=?",
            String.class,
            UUID.fromString(order.get("id").asString()));
    String url = "/api/bookings/events/" + event.getId() + "/dashboard";
    for (int n = 0; n < 2; n++)
      mvc.perform(delete(url).with(as(user)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.removed").value(true));
    mvc.perform(get("/api/bookings/me").with(as(user))).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/payments/orders/me").with(as(user)))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/bookings/me").with(as(other))).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    mvc.perform(get("/api/tickets/" + ticket).with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("INACTIVE"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM booking WHERE event_id=?", Integer.class, event.getId()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM customer_hidden_event WHERE event_id=?",
            Integer.class,
            event.getId()));
    assertEquals(
        refundBefore,
        jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM refund_job r WHERE order_id=?",
            String.class,
            UUID.fromString(order.get("id").asString())));
  }

  @Test
  void ticketRemovalRejectsActiveEventsOtherAccountsAndAnonymousCallers() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    verify(order);
    String url = "/api/bookings/events/" + event.getId() + "/dashboard";
    mvc.perform(delete(url)).andExpect(status().isUnauthorized());
    mvc.perform(delete(url).with(as(other))).andExpect(status().isNotFound());
    mvc.perform(delete(url).with(as(user))).andExpect(status().isConflict());
    jdbc.update("UPDATE event SET publication_status='CANCELLED' WHERE id=?", event.getId());
    mvc.perform(delete(url).with(as(other))).andExpect(status().isNotFound());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM customer_hidden_event WHERE event_id=?",
            Integer.class,
            event.getId()));
    mvc.perform(get("/api/payments/orders/me").with(as(user)))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void deletedEventLegacyTicketsCanBeRemovedWithoutDeletingTheirBooking() throws Exception {
    var legacy =
        bookings.saveAndFlush(
            Booking.builder()
                .event(event)
                .seat(rows.getFirst())
                .user(user)
                .status("CONFIRMED")
                .build());
    jdbc.update("UPDATE event SET publication_status='DELETED' WHERE id=?", event.getId());
    mvc.perform(delete("/api/bookings/events/" + event.getId() + "/dashboard").with(as(user)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/bookings/me").with(as(user))).andExpect(jsonPath("$.length()").value(0));
    assertTrue(bookings.existsById(legacy.getId()));
  }

  @Test
  void cancelledOrDeletedPaidEventsKeepReceiptAndBookedSeatHistory() throws Exception {
    organizerOwnsFixture();
    event.setImageUrl("https://example.com/event-poster.jpg");
    events.saveAndFlush(event);
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
        .andExpect(jsonPath("$[0].event.status").value("CANCELLED"))
        .andExpect(jsonPath("$[0].event.imageUrl").value("https://example.com/event-poster.jpg"));
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
        .andExpect(jsonPath("$[0].event.status").value("DELETED"))
        .andExpect(jsonPath("$[0].event.imageUrl").value("https://example.com/event-poster.jpg"));
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
  void permanentRefundRejectionsRequireReviewInsteadOfRepeatingTheRequest() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new ProviderRequestException(400));
    refunds.processOne();
    assertEquals("REVIEW", refunds.summary(refundOrderId(order)).status());
    assertEquals(
        "Provider HTTP 400; review required",
        jdbc.queryForObject(
            "SELECT last_error FROM refund_job WHERE order_id=?",
            String.class,
            refundOrderId(order)));
    assertFalse(refunds.processOne());
    org.mockito.Mockito.verify(gateway, times(1)).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void throttledRefundRetainsOriginalRequestForBoundedRetry() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new ProviderRequestException(429));
    refunds.processOne();
    assertEquals("RETRY", refunds.summary(refundOrderId(order)).status());
    assertEquals(
        "Provider HTTP 429; retry scheduled",
        jdbc.queryForObject(
            "SELECT last_error FROM refund_job WHERE order_id=?",
            String.class,
            refundOrderId(order)));
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

  JsonNode ticketDetails(UUID id, AppUser who) throws Exception {
    return json.readTree(
        mvc.perform(get("/api/tickets/" + id).with(as(who)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  UUID firstTicket(JsonNode order) {
    return ticketService.forOrder(refundOrderId(order)).getFirst().id();
  }

  void entryTime() {
    event.setStartTime(LocalDateTime.now(ZoneId.of(event.getTimezone())).plusHours(1));
    event.setEndTime(LocalDateTime.now(ZoneId.of(event.getTimezone())).plusHours(3));
    event = events.saveAndFlush(event);
  }

  JsonNode scanTicket(UUID id, AppUser actor) throws Exception {
    String credential = ticketDetails(id, user).get("credential").asString();
    return scanCode(credential, actor);
  }

  JsonNode scanCode(String code, AppUser actor) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/api/organizer/events/" + event.getId() + "/check-in")
                    .with(as(actor))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("credential", code))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void mixedPaidCheckoutIssuesOnePrivateCredentialPerGuestAndReplaysAreStable() throws Exception {
    organizerOwnsFixture();
    var order = create(request(selected(1), type.getId(), 2));
    assertTrue(ticketService.forOrder(refundOrderId(order)).isEmpty());
    payment(order, "captured");
    verify(order);
    verify(order);
    var issued = ticketService.forOrder(refundOrderId(order));
    assertEquals(3, issued.size());
    assertEquals(
        3,
        issued.stream()
            .map(t -> ticketDetailsUnchecked(t.id()).get("credential").asString())
            .distinct()
            .count());
    var before = issued.stream().map(com.tickit.ticket.TicketService.Ticket::id).toList();
    ticketService.recoverIssuance();
    assertEquals(
        before,
        ticketService.forOrder(refundOrderId(order)).stream()
            .map(com.tickit.ticket.TicketService.Ticket::id)
            .toList());
    String history =
        mvc.perform(get("/api/payments/orders/me").with(as(user)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(history.contains("tickit:v1:"));
    assertTrue(history.contains("tickets"));
  }

  JsonNode ticketDetailsUnchecked(UUID id) {
    try {
      return ticketDetails(id, user);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Test
  void qrImageDecodesToExactCredentialAndIsPrivateToThePurchaser() throws Exception {
    var order = paidForRefund();
    UUID ticket = firstTicket(order);
    mvc.perform(get("/api/tickets/" + ticket)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/tickets/" + ticket).with(as(other))).andExpect(status().isNotFound());
    mvc.perform(get("/api/tickets/" + ticket + "/qr").with(as(other)))
        .andExpect(status().isNotFound());
    var bytes =
        mvc.perform(get("/api/tickets/" + ticket + "/qr").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentType("image/png"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
    int w = image.getWidth(), h = image.getHeight();
    var source =
        new com.google.zxing.RGBLuminanceSource(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    var result =
        new com.google.zxing.MultiFormatReader()
            .decode(
                new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(source)));
    assertEquals(ticketDetails(ticket, user).get("credential").asString(), result.getText());
  }

  @Test
  void checkInIsSingleUseAndUsedTicketsNoLongerExposeQr() throws Exception {
    entryTime();
    var order = paidForRefund();
    UUID ticket = firstTicket(order);
    String credential = ticketDetails(ticket, user).get("credential").asString();
    assertEquals("ADMITTED", scanCode(credential, user).get("outcome").asString());
    assertEquals("ALREADY_USED", scanCode(credential, user).get("outcome").asString());
    assertEquals("USED", ticketDetails(ticket, user).path("ticket").path("status").asString());
    assertTrue(ticketDetails(ticket, user).get("credential").isNull());
    mvc.perform(get("/api/tickets/" + ticket + "/qr").with(as(user)))
        .andExpect(status().isConflict());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM admission_scan WHERE event_id=?", Integer.class, event.getId()));
  }

  @Test
  void concurrentScansAdmitExactlyOneGuest() throws Exception {
    entryTime();
    var order = paidForRefund();
    String credential = ticketDetails(firstTicket(order), user).get("credential").asString();
    var executor = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<String>>();
      for (int n = 0; n < 2; n++)
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  return scanCode(credential, user).get("outcome").asString();
                }));
      gate.countDown();
      var results = new ArrayList<String>();
      for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter("ADMITTED"::equals).count());
      assertEquals(1, results.stream().filter("ALREADY_USED"::equals).count());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cancellationRefundAndEntryWindowAreCheckedAtScanTime() throws Exception {
    var order = paidForRefund();
    String code = ticketDetails(firstTicket(order), user).get("credential").asString();
    assertEquals("TOO_EARLY", scanCode(code, user).get("outcome").asString());
    entryTime();
    cancelForRefund();
    assertEquals("INACTIVE", scanCode(code, user).get("outcome").asString());
    assertTrue(ticketDetails(firstTicket(order), user).get("credential").isNull());
    event = events.findById(event.getId()).orElseThrow();
    event.setPublicationStatus("PUBLISHED");
    events.saveAndFlush(event);
    assertEquals("INACTIVE", scanCode(code, user).get("outcome").asString());
  }

  @Test
  void checkInRejectsEndedAndUnknownCredentialsWithoutPersistingTokens() throws Exception {
    entryTime();
    var order = paidForRefund();
    String code = ticketDetails(firstTicket(order), user).get("credential").asString();
    event.setStartTime(LocalDateTime.now(ZoneId.of(event.getTimezone())).minusHours(5));
    event.setEndTime(LocalDateTime.now(ZoneId.of(event.getTimezone())).minusHours(1));
    event = events.saveAndFlush(event);
    assertEquals("ENDED", scanCode(code, user).get("outcome").asString());
    assertEquals(
        "INVALID", scanCode("tickit:v1:" + "A".repeat(43), user).get("outcome").asString());
    assertNull(ticketService.forOrder(refundOrderId(order)).getFirst().checkedInAt());
  }

  @Test
  void authorizedStaffCanScanButCannotReadGuestFinancialsOrGrantAccessAndRevocationIsImmediate()
      throws Exception {
    entryTime();
    var order = paidForRefund();
    String code = ticketDetails(firstTicket(order), user).get("credential").asString();
    String base = "/api/organizer/events/" + event.getId();
    mvc.perform(
            post(base + "/check-in")
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", code))))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(base + "/staff")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", other.getEmail()))))
        .andExpect(status().isOk());
    mvc.perform(get(base + "/operations").with(as(other))).andExpect(status().isNotFound());
    mvc.perform(get(base + "/guests.csv").with(as(other))).andExpect(status().isNotFound());
    mvc.perform(
            post(base + "/staff")
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", user.getEmail()))))
        .andExpect(status().isNotFound());
    assertEquals("ADMITTED", scanCode(code, other).get("outcome").asString());
    mvc.perform(delete(base + "/staff/" + other.getId()).with(as(user))).andExpect(status().isOk());
    mvc.perform(
            post(base + "/check-in")
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", code))))
        .andExpect(status().isNotFound());
  }

  @Test
  void uploadedQrUsesSameSingleUseValidationAndRejectsNonImages() throws Exception {
    entryTime();
    var order = paidForRefund();
    UUID ticket = firstTicket(order);
    var bytes =
        mvc.perform(get("/api/tickets/" + ticket + "/qr").with(as(user)))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    String path = "/api/organizer/events/" + event.getId() + "/check-in/image";
    mvc.perform(
            multipart(path)
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "ticket.png", "image/png", bytes))
                .with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ADMITTED"));
    mvc.perform(
            multipart(path)
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "ticket.png", "image/png", bytes))
                .with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ALREADY_USED"));
    mvc.perform(
            multipart(path)
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "bad.png", "image/png", "not an image".getBytes()))
                .with(as(user)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsAreOwnerOnlyAndCsvEscapesSpreadsheetFormulas() throws Exception {
    var order = paidForRefund();
    UUID ticket = firstTicket(order);
    String base = "/api/organizer/events/" + event.getId();
    jdbc.update("UPDATE checkout_item SET label='=1+1' WHERE order_id=?", refundOrderId(order));
    mvc.perform(get(base + "/operations").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admissions.issued").value(1))
        .andExpect(jsonPath("$.financials.captured_amount").value(12345));
    var csv =
        mvc.perform(get(base + "/guests.csv").with(as(user)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(csv.contains("\"'=1+1\""));
    assertFalse(csv.contains("tickit:v1:"));
    mvc.perform(get(base + "/operations?page=-1").with(as(user)))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/entry/events").with(as(other)))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void durableNotificationsAreDeduplicatedRetryableAndDisabledDoesNotPretendToSend()
      throws Exception {
    var order = paidForRefund();
    outbox.booking(refundOrderId(order));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE event_id=?",
            Integer.class,
            event.getId()));
    assertFalse(notificationWorker.processOne());
    verifyNoInteractions(mail);
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    doThrow(new IllegalStateException("private SMTP credentials"))
        .doNothing()
        .when(mail)
        .send(any(), anyString(), anyString(), anyString());
    assertTrue(notificationWorker.processOne());
    assertEquals(
        "RETRY",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE event_id=?",
            String.class,
            event.getId()));
    assertFalse(
        jdbc.queryForObject(
                "SELECT last_error FROM notification_outbox WHERE event_id=?",
                String.class,
                event.getId())
            .contains("credentials"));
    jdbc.update(
        "UPDATE notification_outbox SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE"
            + " event_id=?",
        event.getId());
    assertTrue(notificationWorker.processOne());
    assertEquals(
        "SENT",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE event_id=?",
            String.class,
            event.getId()));
    assertFalse(notificationWorker.processOne());
    outbox.recover();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE event_id=?",
            Integer.class,
            event.getId()));
  }

  @Test
  void rolledBackBookingCannotLeaveTicketsOrNotificationJobs() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    transaction.executeWithoutResult(
        s -> {
          jdbc.update(
              "UPDATE checkout_order SET status='CONFIRMED',provider_payment_id='pay_fixture' WHERE"
                  + " id=?",
              refundOrderId(order));
          ticketService.issue(refundOrderId(order));
          outbox.booking(refundOrderId(order));
          assertEquals(1, ticketService.forOrder(refundOrderId(order)).size());
          assertEquals(
              1,
              jdbc.queryForObject(
                  "SELECT count(*) FROM notification_outbox WHERE event_id=?",
                  Integer.class,
                  event.getId()));
          s.setRollbackOnly();
        });
    assertTrue(ticketService.forOrder(refundOrderId(order)).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE event_id=?",
            Integer.class,
            event.getId()));
  }

  @Test
  void eventAndRefundNotificationsSurviveRestartRecoveryWithoutDuplicates() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE event_id=? AND kind='EVENT_CHANGE'",
            Integer.class,
            event.getId()));
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenReturn(refundResponse(order, "processed"));
    refunds.processOne();
    outbox.recover();
    outbox.recover();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE event_id=? AND kind='REFUND'",
            Integer.class,
            event.getId()));
  }

  @Test
  void operatorCanInspectAndRecoverMatchingRefundWithoutCreatingAnotherRequest() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new ProviderRequestException(400));
    refunds.processOne();
    when(gateway.refunds("pay_fixture")).thenReturn(List.of(refundResponse(order, "processed")));
    var dev = jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"));
    String path = "/api/developer/payments/refunds/" + refundOrderId(order);
    mvc.perform(post(path + "/inspect").with(as(user))).andExpect(status().isForbidden());
    mvc.perform(post(path + "/inspect").with(dev))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refund.status").value("PROCESSED"));
    mvc.perform(
            post(path + "/retry")
                .with(dev)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Reviewed provider state\"}"))
        .andExpect(status().isConflict());
    org.mockito.Mockito.verify(gateway, times(1)).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void auditedOperatorRetryPreservesRefundKeyAndRequiresReasonAndAuthorization() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    when(gateway.createRefund(anyString(), anyLong(), any()))
        .thenThrow(new ProviderRequestException(400));
    refunds.processOne();
    var dev = jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"));
    String path = "/api/developer/payments/refunds/" + refundOrderId(order) + "/retry";
    mvc.perform(
            post(path)
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Provider issue resolved\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(dev)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(path)
                .with(dev)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Provider confirmed issue resolved\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RETRY"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM refund_operation WHERE order_id=?",
            Integer.class,
            refundOrderId(order)));
    doReturn(refundResponse(order, "processed"))
        .when(gateway)
        .createRefund(anyString(), anyLong(), any());
    refunds.processOne();
    assertEquals("PROCESSED", refunds.summary(refundOrderId(order)).status());
    org.mockito.Mockito.verify(gateway, times(2))
        .createRefund("pay_fixture", order.get("amount").asLong(), refundOrderId(order));
  }

  @Test
  void notificationAttemptsAreBoundedAndCompetingWorkersDoNotDuplicateClaim() throws Exception {
    var order = paidForRefund();
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            a -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(mail)
        .send(any(), anyString(), anyString(), anyString());
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(() -> notificationWorker.processOne());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertFalse(notificationWorker.processOne());
      release.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    org.mockito.Mockito.verify(mail, times(1)).send(any(), anyString(), anyString(), anyString());
    jdbc.update(
        "UPDATE notification_outbox SET"
            + " status='RETRY',attempts=7,next_attempt_at=clock_timestamp()-interval '1 second'"
            + " WHERE event_id=?",
        event.getId());
    doThrow(new IllegalStateException("transport failed"))
        .when(mail)
        .send(any(), anyString(), anyString(), anyString());
    assertTrue(notificationWorker.processOne());
    assertFalse(notificationWorker.processOne());
    assertEquals(
        "FAILED",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE event_id=?",
            String.class,
            event.getId()));
  }

  @Test
  void ownerOfTwoEventsCannotAdmitOneEventsTicketAtTheOtherEvent() throws Exception {
    entryTime();
    var order = paidForRefund();
    UUID ticket = firstTicket(order);
    String code = ticketDetails(ticket, user).get("credential").asString();
    var another =
        events.saveAndFlush(
            Event.builder()
                .name("Another event")
                .currency("INR")
                .city("Mumbai")
                .startTime(event.getStartTime())
                .organizer(event.getOrganizer())
                .build());
    try {
      mvc.perform(
              post("/api/organizer/events/" + another.getId() + "/check-in")
                  .with(as(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("credential", code))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.outcome").value("INVALID"));
      assertNull(ticketService.forOrder(refundOrderId(order)).getFirst().checkedInAt());
      assertEquals("ADMITTED", scanCode(code, user).get("outcome").asString());
    } finally {
      events.deleteById(another.getId());
    }
  }

  @Test
  void rescheduledTicketsRetainCredentialAndUseTheNewEntryWindow() throws Exception {
    entryTime();
    var order = paidForRefund();
    UUID id = firstTicket(order);
    String code = ticketDetails(id, user).get("credential").asString();
    var edit = eventForEditing();
    var next = LocalDateTime.now(ZoneId.of(event.getTimezone())).plusDays(5);
    edit.put("startTime", next.toString());
    edit.put("endTime", next.plusHours(3).toString());
    edit.put("changeReason", "New venue availability");
    mvc.perform(
            put("/api/organizer/events/" + event.getId())
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit)))
        .andExpect(status().isOk());
    assertEquals(code, ticketDetails(id, user).get("credential").asString());
    assertEquals("TOO_EARLY", scanCode(code, user).get("outcome").asString());
    assertNull(ticketService.forOrder(refundOrderId(order)).getFirst().checkedInAt());
  }

  @Test
  void revocationCommittedWhileScanWaitsForEventLockPreventsAdmission() throws Exception {
    entryTime();
    var order = paidForRefund();
    String code = ticketDetails(firstTicket(order), user).get("credential").asString();
    jdbc.update(
        "INSERT INTO event_staff(event_id,user_id,granted_by) VALUES(?,?,?)",
        event.getId(),
        other.getId(),
        user.getId());
    var passedFirstCheck = new CountDownLatch(1);
    doAnswer(
            a -> {
              var result = a.callRealMethod();
              passedFirstCheck.countDown();
              return result;
            })
        .when(ticketAccess)
        .scanner(event.getId());
    var executor = Executors.newSingleThreadExecutor();
    var pending = new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
    try {
      transaction.executeWithoutResult(
          s -> {
            jdbc.queryForObject(
                "SELECT id FROM event WHERE id=? FOR UPDATE", Long.class, event.getId());
            jdbc.update(
                "DELETE FROM event_staff WHERE event_id=? AND user_id=?",
                event.getId(),
                other.getId());
            pending.set(
                executor.submit(
                    () ->
                        mvc.perform(
                                post("/api/organizer/events/" + event.getId() + "/check-in")
                                    .with(as(other))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(Map.of("credential", code))))
                            .andReturn()
                            .getResponse()
                            .getStatus()));
            try {
              assertTrue(passedFirstCheck.await(15, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(ex);
            }
          });
      assertEquals(404, pending.get().get(20, TimeUnit.SECONDS));
      assertNull(ticketService.forOrder(refundOrderId(order)).getFirst().checkedInAt());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void scannerRejectsOversizedDimensionsAndMalformedImageWithoutConsumingTicket() throws Exception {
    entryTime();
    var order = paidForRefund();
    var image =
        new java.awt.image.BufferedImage(2049, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "PNG", bytes);
    String path = "/api/organizer/events/" + event.getId() + "/check-in/image";
    for (byte[] input :
        List.of(
            bytes.toByteArray(),
            new byte[2 * 1024 * 1024 + 1],
            new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      mvc.perform(
              multipart(path)
                  .file(
                      new org.springframework.mock.web.MockMultipartFile(
                          "file", "ticket.png", "image/png", input))
                  .with(as(user)))
          .andExpect(status().isBadRequest());
    assertNull(ticketService.forOrder(refundOrderId(order)).getFirst().checkedInAt());
  }

  @Test
  void cancelledBookingConfirmationIsSuppressedButLatestCancellationNoticeIsSent()
      throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    assertTrue(notificationWorker.processOne());
    verifyNoInteractions(mail);
    assertEquals(
        "SUPPRESSED",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE dedupe_key=?",
            String.class,
            "booking:" + refundOrderId(order)));
    assertTrue(notificationWorker.processOne());
    org.mockito.Mockito.verify(mail, times(1))
        .send(any(), eq(user.getEmail()), eq("Update to your TickIt event"), anyString());
  }

  @Test
  void restrictedDeliveryLeavesOtherRecipientsQueuedAndCannotBypassTransportScope()
      throws Exception {
    var order = paidForRefund();
    emailSettings.setAllowedRecipients("somebody-else@tickit.test");
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    assertFalse(notificationWorker.processOne());
    verifyNoInteractions(mail);
    emailSettings.setAllowedRecipients(user.getEmail().toUpperCase(java.util.Locale.ROOT));
    assertTrue(notificationWorker.processOne());
    org.mockito.Mockito.verify(mail, times(1))
        .send(any(), eq(user.getEmail()), anyString(), anyString());
  }

  @Test
  void developerNotificationReviewIsAuditedAndCannotChangeAcceptedOrClaimedMessages()
      throws Exception {
    var order = paidForRefund();
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM notification_outbox WHERE event_id=?", UUID.class, event.getId());
    String path = "/api/developer/notifications/" + id + "/review";
    var dev = jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"));
    String retry = "{\"action\":\"RETRY\",\"reason\":\"Provider configuration corrected\"}";
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(retry))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(retry))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).with(dev).contentType(MediaType.APPLICATION_JSON).content(retry))
        .andExpect(status().isConflict());
    jdbc.update("UPDATE notification_outbox SET status='FAILED',attempts=8 WHERE id=?", id);
    mvc.perform(post(path).with(dev).contentType(MediaType.APPLICATION_JSON).content(retry))
        .andExpect(status().isOk());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT attempts FROM notification_outbox WHERE id=?", Integer.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_operation WHERE notification_id=?",
            Integer.class,
            id));
    jdbc.update(
        "UPDATE notification_outbox SET lease_token=?,lease_until=clock_timestamp()+interval '90"
            + " seconds' WHERE id=?",
        UUID.randomUUID(),
        id);
    String suppress = "{\"action\":\"SUPPRESS\",\"reason\":\"Reviewed historical message\"}";
    mvc.perform(post(path).with(dev).contentType(MediaType.APPLICATION_JSON).content(suppress))
        .andExpect(status().isConflict());
    jdbc.update("UPDATE notification_outbox SET lease_until=NULL,status='SENT' WHERE id=?", id);
    mvc.perform(post(path).with(dev).contentType(MediaType.APPLICATION_JSON).content(suppress))
        .andExpect(status().isConflict());
  }

  @Test
  void queuedRefundFailureNoticeIsSuppressedAfterRefundProcessed() throws Exception {
    var order = paidForRefund();
    cancelForRefund();
    jdbc.update("UPDATE refund_job SET status='FAILED' WHERE order_id=?", refundOrderId(order));
    outbox.refund(refundOrderId(order));
    jdbc.update("UPDATE refund_job SET status='PROCESSED' WHERE order_id=?", refundOrderId(order));
    outbox.refund(refundOrderId(order));
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    for (int n = 0; n < 5 && notificationWorker.processOne(); n++) {}
    assertEquals(
        "SUPPRESSED",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE dedupe_key=?",
            String.class,
            "refund:" + refundOrderId(order) + ":FAILED"));
    assertEquals(
        "SENT",
        jdbc.queryForObject(
            "SELECT status FROM notification_outbox WHERE dedupe_key=?",
            String.class,
            "refund:" + refundOrderId(order) + ":PROCESSED"));
  }

  @Test
  void heldBacklogRequiresExplicitAuditedReleaseBeforeDelivery() throws Exception {
    var order = paidForRefund();
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM notification_outbox WHERE event_id=?", UUID.class, event.getId());
    jdbc.update("UPDATE notification_outbox SET status='HELD' WHERE id=?", id);
    org.springframework.test.util.ReflectionTestUtils.setField(notificationWorker, "enabled", true);
    assertFalse(notificationWorker.processOne());
    verifyNoInteractions(mail);
    var dev = jwt().jwt(j -> j.issuer(ISSUER).subject("payment_test_developer"));
    mvc.perform(
            post("/api/developer/notifications/" + id + "/review")
                .with(dev)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"action\":\"RELEASE\",\"reason\":\"Reviewed existing customer receipt\"}"))
        .andExpect(status().isOk());
    assertEquals(
        "Released after operator review",
        jdbc.queryForObject(
            "SELECT last_error FROM notification_outbox WHERE id=?", String.class, id));
    assertTrue(notificationWorker.processOne());
    assertEquals(
        "SENT",
        jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id=?", String.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_operation WHERE notification_id=? AND"
                + " action='RELEASE'",
            Integer.class,
            id));
  }

  JsonNode confirmedCustomerOrder() throws Exception {
    var order = create(request(selected(1), null, 0));
    payment(order, "captured");
    verify(order);
    return order;
  }

  UUID offerTransfer(UUID ticket, AppUser recipient) throws Exception {
    return UUID.fromString(
        json.readTree(
                mvc.perform(
                        post("/api/tickets/" + ticket + "/transfer")
                            .with(as(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(Map.of("email", recipient.getEmail()))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asString());
  }

  void resolveTransfer(UUID id, AppUser actor, String action) throws Exception {
    mvc.perform(
            post("/api/account/transfers/" + id)
                .with(as(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("action", action, "age", 25))))
        .andExpect(status().isOk());
  }

  @Test
  void savedEventsArePrivateIdempotentAndRequirePublicListings() throws Exception {
    mvc.perform(put("/api/account/saved-events/" + event.getId()).with(as(user)))
        .andExpect(status().isOk());
    mvc.perform(put("/api/account/saved-events/" + event.getId()).with(as(user)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/account/saved-events").with(as(user)))
        .andExpect(jsonPath("$[0]").value(event.getId()));
    mvc.perform(get("/api/account/saved-events").with(as(other))).andExpect(content().json("[]"));
    mvc.perform(delete("/api/account/saved-events/" + event.getId()).with(as(other)))
        .andExpect(status().isOk());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM saved_event WHERE user_id=?", Integer.class, user.getId()));
    event.setPublicationStatus("DRAFT");
    events.saveAndFlush(event);
    mvc.perform(put("/api/account/saved-events/" + event.getId()).with(as(other)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/account/saved-events")).andExpect(status().isUnauthorized());
  }

  @Test
  void supportRequestsAreOrderOwnedBoundedAndDeveloperResolved() throws Exception {
    var order = confirmedCustomerOrder();
    String input =
        json.writeValueAsString(
            Map.of(
                "orderId",
                order.get("id").asString(),
                "category",
                "ENTRY",
                "message",
                "Where is the accessible entrance?"));
    mvc.perform(
            post("/api/account/support")
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isNotFound());
    UUID id =
        UUID.fromString(
            json.readTree(
                    mvc.perform(
                            post("/api/account/support")
                                .with(as(user))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(input))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
    mvc.perform(get("/api/account/support").with(as(other))).andExpect(content().json("[]"));
    mvc.perform(
            post("/api/developer/support/" + id + "/reply")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reply\":\"Use the north entrance.\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/developer/support/" + id + "/reply")
                .with(jwt().jwt(j -> j.subject("payment_test_developer")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reply\":\"Use the north entrance.\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/account/support").with(as(user)))
        .andExpect(jsonPath("$[0].status").value("RESOLVED"))
        .andExpect(jsonPath("$[0].reply").value("Use the north entrance."));
    for (int n = 0; n < 4; n++)
      mvc.perform(
              post("/api/account/support")
                  .with(as(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(input))
          .andExpect(status().isCreated());
    mvc.perform(
            post("/api/account/support")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void confirmationResendCreatesFreshIntentWithoutReleasingHeldMail() throws Exception {
    var order = confirmedCustomerOrder();
    UUID id = refundOrderId(order);
    jdbc.update("UPDATE notification_outbox SET status='HELD' WHERE order_id=?", id);
    mvc.perform(post("/api/account/orders/" + id + "/resend").with(as(other)))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/account/orders/" + id + "/resend").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"));
    mvc.perform(post("/api/account/orders/" + id + "/resend").with(as(user)))
        .andExpect(status().isTooManyRequests());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE order_id=? AND status='HELD'",
            Integer.class,
            id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_outbox WHERE order_id=? AND status='QUEUED' AND"
                + " dedupe_key LIKE 'resend:%'",
            Integer.class, id));
    org.mockito.Mockito.verifyNoInteractions(mail);
  }

  @Test
  void acceptedTransferRotatesQrAndSeparatesAdmissionFromFinancialOwnership() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order);
    String original = ticketDetails(ticket, user).get("credential").asString();
    UUID transfer = offerTransfer(ticket, other);
    mvc.perform(get("/api/account/transfers").with(as(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].minimum_age").value(18))
        .andExpect(jsonPath("$[0].incoming").value(true));
    resolveTransfer(transfer, other, "ACCEPT");
    resolveTransfer(transfer, other, "ACCEPT");
    mvc.perform(get("/api/tickets/" + ticket).with(as(user))).andExpect(status().isNotFound());
    String replacement = ticketDetails(ticket, other).get("credential").asString();
    assertNotEquals(original, replacement);
    mvc.perform(get("/api/payments/orders/" + order.get("id").asString()).with(as(other)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/account/received-tickets").with(as(other)))
        .andExpect(jsonPath("$[0].ticket.id").value(ticket.toString()))
        .andExpect(jsonPath("$[0].credential").doesNotExist());
    assertEquals("TRANSFERRED", ticketService.forOrder(refundOrderId(order)).getFirst().status());
    organizerOwnsFixture();
    mvc.perform(get("/api/organizer/events/" + event.getId() + "/operations").with(as(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.guests[0].email").value(other.getEmail()));
    entryTime();
    assertEquals("INVALID", scanCode(original, user).get("outcome").asString());
    assertEquals("ADMITTED", scanCode(replacement, user).get("outcome").asString());
    assertEquals("ALREADY_USED", scanCode(replacement, user).get("outcome").asString());
  }

  @Test
  void transferRequiresCorrectActorAgeAndUnexpiredOffer() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order), transfer = offerTransfer(ticket, other);
    mvc.perform(
            post("/api/account/transfers/" + transfer)
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"ACCEPT\",\"age\":25}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/account/transfers/" + transfer)
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"ACCEPT\",\"age\":17}"))
        .andExpect(status().isBadRequest());
    jdbc.update(
        "UPDATE ticket_transfer SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        transfer);
    mvc.perform(
            post("/api/account/transfers/" + transfer)
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"ACCEPT\",\"age\":25}"))
        .andExpect(status().isConflict());
    assertNotNull(ticketDetails(ticket, user).get("credential"));
  }

  @Test
  void declineAndCancellationDoNotRotateValidQr() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order);
    String original = ticketDetails(ticket, user).get("credential").asString();
    UUID first = offerTransfer(ticket, other);
    resolveTransfer(first, other, "DECLINE");
    UUID second = offerTransfer(ticket, other);
    resolveTransfer(second, user, "CANCEL");
    assertEquals(original, ticketDetails(ticket, user).get("credential").asString());
    mvc.perform(get("/api/tickets/" + ticket).with(as(other))).andExpect(status().isNotFound());
  }

  @Test
  void transferCannotBypassRecipientTicketLimit() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order), transfer = offerTransfer(ticket, other);
    jdbc.update(
        "INSERT INTO booking(event_id,seat_id,user_id,status)"
            + " VALUES(?,?,?,'CONFIRMED'),(?,?,?,'CONFIRMED'),(?,?,?,'CONFIRMED')",
        event.getId(),
        rows.get(1).getId(),
        other.getId(),
        event.getId(),
        rows.get(2).getId(),
        other.getId(),
        event.getId(),
        rows.get(3).getId(),
        other.getId());
    mvc.perform(
            post("/api/account/transfers/" + transfer)
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"ACCEPT\",\"age\":25}"))
        .andExpect(status().isConflict());
    assertNotNull(ticketDetails(ticket, user).get("credential"));
  }

  @Test
  void customerCancellationSnapshotsPolicyQueuesOneFullRefundAndInvalidatesQr() throws Exception {
    event.setCancellationCutoffHours(24);
    event = events.saveAndFlush(event);
    var order = confirmedCustomerOrder();
    UUID id = refundOrderId(order), ticket = firstTicket(order);
    assertNotNull(
        jdbc.queryForObject(
            "SELECT cancellation_deadline FROM checkout_order WHERE id=?",
            java.sql.Timestamp.class,
            id));
    event.setCancellationCutoffHours(null);
    events.saveAndFlush(event);
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(other)))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(user)))
        .andExpect(status().isOk());
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(user)))
        .andExpect(status().isOk());
    assertEquals("CUSTOMER_CANCELLED", refunds.summary(id).reason());
    assertEquals(order.get("amount").asLong(), refunds.summary(id).amount());
    assertTrue(ticketDetails(ticket, user).get("credential").isNull());
    org.mockito.Mockito.verify(gateway, never()).createRefund(anyString(), anyLong(), any());
  }

  @Test
  void noPolicyOrExpiredDeadlineCannotCancelAnOrder() throws Exception {
    var order = confirmedCustomerOrder();
    UUID id = refundOrderId(order);
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(user)))
        .andExpect(status().isConflict());
    jdbc.update(
        "UPDATE checkout_order SET cancellation_deadline=clock_timestamp()-interval '1 second'"
            + " WHERE id=?",
        id);
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(user)))
        .andExpect(status().isConflict());
    assertNull(refunds.summary(id));
  }

  @Test
  void transferredOrderCannotBeCancelledByPurchaser() throws Exception {
    event.setCancellationCutoffHours(24);
    event = events.saveAndFlush(event);
    var order = confirmedCustomerOrder();
    UUID transfer = offerTransfer(firstTicket(order), other);
    resolveTransfer(transfer, other, "ACCEPT");
    mvc.perform(post("/api/account/orders/" + refundOrderId(order) + "/cancel").with(as(user)))
        .andExpect(status().isConflict());
    assertNull(refunds.summary(refundOrderId(order)));
  }

  @Test
  void concurrentAcceptanceIsIdempotentAndOnlyOneReplacementQrIsIssued() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order), transfer = offerTransfer(ticket, other);
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var jobs = new ArrayList<Future<?>>();
      for (int n = 0; n < 2; n++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  resolveTransfer(transfer, other, "ACCEPT");
                  return null;
                }));
      start.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM ticket_transfer WHERE id=? AND status='ACCEPTED'",
            Integer.class,
            transfer));
    assertEquals(
        other.getId(),
        jdbc.queryForObject(
            "SELECT holder_id FROM admission_ticket WHERE id=?", Long.class, ticket));
    assertEquals(1, ticketService.forOrder(refundOrderId(order)).size());
  }

  @Test
  void usedAdmissionCannotBeTransferredOrCustomerCancelled() throws Exception {
    event.setCancellationCutoffHours(1);
    event = events.saveAndFlush(event);
    organizerOwnsFixture();
    var order = confirmedCustomerOrder();
    UUID id = refundOrderId(order), ticket = firstTicket(order);
    entryTime();
    assertEquals("ADMITTED", scanTicket(ticket, user).get("outcome").asString());
    mvc.perform(
            post("/api/tickets/" + ticket + "/transfer")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", other.getEmail()))))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/account/orders/" + id + "/cancel").with(as(user)))
        .andExpect(status().isConflict());
    assertNull(refunds.summary(id));
  }

  @Test
  void cancelledEventInvalidatesReceivedAdmissionAndCanBeHiddenByRecipient() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order);
    resolveTransfer(offerTransfer(ticket, other), other, "ACCEPT");
    event.setPublicationStatus("CANCELLED");
    events.saveAndFlush(event);
    refunds.enqueueEligible(event.getId());
    assertTrue(ticketDetails(ticket, other).get("credential").isNull());
    mvc.perform(delete("/api/bookings/events/" + event.getId() + "/dashboard").with(as(other)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/account/received-tickets").with(as(other)))
        .andExpect(content().json("[]"));
    assertTrue(ticketService.forOrder(refundOrderId(order)).getFirst().status().equals("INACTIVE"));
    assertNotNull(refunds.summary(refundOrderId(order)));
  }

  @Test
  void acceptedAdmissionCountsAgainstRecipientFuturePurchases() throws Exception {
    var order = confirmedCustomerOrder();
    UUID ticket = firstTicket(order);
    resolveTransfer(offerTransfer(ticket, other), other, "ACCEPT");
    assertEquals(1, bookings.countAllTickets(other.getId(), event.getId()));
    assertEquals(1, bookings.countAllTickets(user.getId(), event.getId()));
    mvc.perform(
            post("/api/payments/orders")
                .with(as(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        request(rows.subList(1, 4).stream().map(Seat::getId).toList(), null, 0))))
        .andExpect(status().isConflict());
  }
}
