package com.tickit.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class RazorpayHttpGatewayTest {
  HttpClient client;
  RazorpayHttpGateway gateway;
  PaymentSettings settings;

  @BeforeEach
  void setup() {
    client = mock(HttpClient.class);
    settings = new PaymentSettings();
    settings.setKeyId("rzp_test_fixture");
    settings.setKeySecret("fixture-secret");
    gateway = new RazorpayHttpGateway(settings, new ObjectMapper(), client);
  }

  @SuppressWarnings("unchecked")
  void respond(int status, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(client.send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);
  }

  @Test
  void createsServerPricedOrderWithBasicAuthAndBoundedRequest() throws Exception {
    UUID receipt = UUID.randomUUID();
    respond(
        200,
        "{\"id\":\"order_fixture\",\"amount\":24690,\"currency\":\"INR\",\"receipt\":\""
            + receipt
            + "\"}");
    var order = gateway.create(receipt, 24690, "INR");
    assertEquals(receipt.toString(), order.receipt());
    assertEquals(24690, order.amount());
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any());
    assertEquals("https://api.razorpay.com/v1/orders", request.getValue().uri().toString());
    assertEquals("POST", request.getValue().method());
    assertTrue(request.getValue().timeout().isPresent());
    assertEquals(
        "Basic "
            + Base64.getEncoder()
                .encodeToString("rzp_test_fixture:fixture-secret".getBytes(StandardCharsets.UTF_8)),
        request.getValue().headers().firstValue("Authorization").orElseThrow());
  }

  @Test
  void capturesAndListsActualProviderPaymentFields() throws Exception {
    String body =
        "{\"id\":\"pay_fixture\",\"order_id\":\"order_fixture\",\"amount\":10000,\"currency\":\"INR\",\"status\":\"captured\"}";
    respond(200, body);
    assertEquals("captured", gateway.payment("pay_fixture").status());
    respond(200, "{\"items\":[" + body + "]}");
    assertEquals(10000, gateway.payments("order_fixture").getFirst().amount());
  }

  @Test
  void failuresAreSanitizedAndCreationIsNeverAutomaticallyRetried() throws Exception {
    respond(401, "secret echoed by provider fixture-secret");
    var ex =
        assertThrows(
            ResponseStatusException.class, () -> gateway.create(UUID.randomUUID(), 100, "INR"));
    assertEquals(503, ex.getStatusCode().value());
    assertFalse(ex.getMessage().contains("fixture-secret"));
    verify(client, times(1)).send(any(), any());
  }

  @Test
  void invalidReferenceCannotChangeRequestOriginOrPath() {
    assertThrows(ResponseStatusException.class, () -> gateway.payment("https://another.example/"));
    verifyNoInteractions(client);
  }

  @Test
  void connectionFailureIsRetryableWithoutLeakingDetails() throws Exception {
    when(client.send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new IOException("private network details"));
    var ex = assertThrows(ResponseStatusException.class, () -> gateway.payment("pay_fixture"));
    assertEquals(503, ex.getStatusCode().value());
    assertFalse(ex.getMessage().contains("private network"));
  }

  @Test
  void normalRefundUsesPersistentIdempotencyKeyAndNoInstantSpeed() throws Exception {
    UUID key = UUID.randomUUID();
    respond(
        200,
        "{\"id\":\"rfnd_fixture\",\"payment_id\":\"pay_fixture\",\"amount\":50000,\"currency\":\"INR\",\"status\":\"pending\",\"receipt\":\""
            + key
            + "\"}");
    var refund = gateway.createRefund("pay_fixture", 50000, key);
    assertEquals("pending", refund.status());
    assertEquals(key.toString(), refund.receipt());
    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(captor.capture(), any());
    var request = captor.getValue();
    assertEquals(
        "https://api.razorpay.com/v1/payments/pay_fixture/refund", request.uri().toString());
    assertEquals(
        key.toString(), request.headers().firstValue("X-Refund-Idempotency").orElseThrow());
    var bytes = new java.io.ByteArrayOutputStream();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
              public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              public void onNext(java.nio.ByteBuffer value) {
                while (value.hasRemaining()) bytes.write(value.get());
              }

              public void onError(Throwable error) {
                throw new AssertionError(error);
              }

              public void onComplete() {}
            });
    var body = new ObjectMapper().readTree(bytes.toByteArray());
    assertEquals(50000, body.path("amount").asLong());
    assertEquals("normal", body.path("speed").asString());
    assertEquals(key.toString(), body.path("receipt").asString());
  }

  @Test
  void refundFetchValidatesReferenceAndUsesFixedProviderOrigin() throws Exception {
    assertThrows(
        ResponseStatusException.class, () -> gateway.refund("https://other.example/refund"));
    verifyNoInteractions(client);
    respond(
        200,
        "{\"id\":\"rfnd_fixture\",\"payment_id\":\"pay_fixture\",\"amount\":100,\"currency\":\"INR\",\"status\":\"processed\"}");
    assertEquals("processed", gateway.refund("rfnd_fixture").status());
    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(captor.capture(), any());
    assertEquals(
        "https://api.razorpay.com/v1/refunds/rfnd_fixture", captor.getValue().uri().toString());
    assertEquals("GET", captor.getValue().method());
  }
}
