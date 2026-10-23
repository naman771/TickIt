package com.tickit.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class CheckoutController {
  private final CheckoutService service;

  @ExceptionHandler(CheckoutSetupException.class)
  public org.springframework.http.ResponseEntity<Map<String, Object>> uncertainCreate(
      CheckoutSetupException ex) {
    return org.springframework.http.ResponseEntity.status(503)
        .body(Map.of("status", 503, "error", ex.getMessage(), "orderId", ex.orderId()));
  }

  public record Verification(
      @NotBlank @Pattern(regexp = "pay_[A-Za-z0-9]{1,64}") String razorpay_payment_id,
      @NotBlank @Pattern(regexp = "order_[A-Za-z0-9]{1,64}") String razorpay_order_id,
      @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String razorpay_signature) {}

  @PostMapping("/orders")
  public Map<String, Object> create(@Valid @RequestBody CheckoutRequest request) {
    return service.create(request);
  }

  @GetMapping("/orders/me")
  public List<Map<String, Object>> history() {
    return service.history();
  }

  @GetMapping("/orders/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/orders/{id}/verify")
  public Map<String, Object> verify(
      @PathVariable UUID id, @Valid @RequestBody Verification request) {
    return service.verify(
        id,
        request.razorpay_payment_id(),
        request.razorpay_order_id(),
        request.razorpay_signature());
  }

  @DeleteMapping("/orders/{id}")
  public Map<String, Object> cancel(@PathVariable UUID id) {
    return service.cancel(id);
  }

  @PostMapping("/orders/{id}/reconcile")
  public Map<String, Object> reconcile(@PathVariable UUID id) {
    return service.reconcile(id);
  }

  @PostMapping(
      value = "/webhook",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> webhook(
      @RequestBody byte[] body,
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
    service.webhook(body, signature);
    return Map.of("received", true);
  }
}
