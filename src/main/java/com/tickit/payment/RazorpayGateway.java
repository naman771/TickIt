package com.tickit.payment;

import java.util.List;
import java.util.UUID;

public interface RazorpayGateway {
  record Order(String id, long amount, String currency, String receipt) {}

  record Payment(
      String id, String orderId, long amount, String currency, String status, long amountRefunded) {
    public Payment(String id, String orderId, long amount, String currency, String status) {
      this(id, orderId, amount, currency, status, 0);
    }
  }

  record Refund(
      String id, String paymentId, long amount, String currency, String status, String receipt) {}

  Refund createRefund(String paymentId, long amount, UUID idempotencyKey);

  Refund refund(String id);

  List<Refund> refunds(String paymentId);

  Order create(UUID receipt, long amount, String currency);

  Order order(String id);

  Payment payment(String id);

  List<Payment> payments(String orderId);
}
