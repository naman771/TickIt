package com.tickit.payment;

import java.util.List;
import java.util.UUID;

public interface RazorpayGateway {
  record Order(String id, long amount, String currency, String receipt) {}

  record Payment(String id, String orderId, long amount, String currency, String status) {}

  Order create(UUID receipt, long amount, String currency);

  Order order(String id);

  Payment payment(String id);

  List<Payment> payments(String orderId);
}
