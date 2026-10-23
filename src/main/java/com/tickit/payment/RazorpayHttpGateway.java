package com.tickit.payment;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/**
 * Fixed provider origin, bounded timeouts, no automatic order-creation retries or secret logging.
 */
@Component
public class RazorpayHttpGateway implements RazorpayGateway {
  private final PaymentSettings settings;
  private final ObjectMapper json;
  private final HttpClient client;

  @org.springframework.beans.factory.annotation.Autowired
  public RazorpayHttpGateway(PaymentSettings settings, ObjectMapper json) {
    this(settings, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  RazorpayHttpGateway(PaymentSettings settings, ObjectMapper json, HttpClient client) {
    this.settings = settings;
    this.json = json;
    this.client = client;
  }

  public Order create(UUID receipt, long amount, String currency) {
    return asOrder(
        call(
            "POST",
            "orders",
            Map.of(
                "amount",
                amount,
                "currency",
                currency,
                "receipt",
                receipt.toString(),
                "partial_payment",
                false)));
  }

  public Order order(String id) {
    return asOrder(call("GET", "orders/" + id(id), null));
  }

  public Payment payment(String id) {
    return asPayment(call("GET", "payments/" + id(id), null));
  }

  public List<Payment> payments(String orderId) {
    return call("GET", "orders/" + id(orderId) + "/payments", null)
        .path("items")
        .valueStream()
        .map(this::asPayment)
        .toList();
  }

  private String id(String id) {
    if (id == null || !id.matches("(?:order|pay)_[A-Za-z0-9]{1,64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment reference");
    return id;
  }

  private Order asOrder(JsonNode n) {
    return new Order(
        n.path("id").asText(),
        n.path("amount").asLong(),
        n.path("currency").asText(),
        n.path("receipt").asText());
  }

  private Payment asPayment(JsonNode n) {
    return new Payment(
        n.path("id").asText(),
        n.path("order_id").asText(),
        n.path("amount").asLong(),
        n.path("currency").asText(),
        n.path("status").asText());
  }

  private JsonNode call(String method, String path, Object body) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/" + path))
              .timeout(Duration.ofSeconds(12))
              .header("Accept", "application/json")
              .header(
                  "Authorization",
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString(
                              (settings.getKeyId() + ":" + settings.getKeySecret())
                                  .getBytes(StandardCharsets.UTF_8)));
      if (body != null)
        request
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      else request.GET();
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() > 299)
        throw new IllegalStateException();
      return json.readTree(response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Payment provider is temporarily unavailable. Check your order before retrying.");
  }
}
