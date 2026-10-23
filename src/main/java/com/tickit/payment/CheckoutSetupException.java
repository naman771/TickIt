package com.tickit.payment;

import java.util.UUID;

/**
 * Retain an owned reference when order creation times out after the local transaction committed.
 */
public class CheckoutSetupException extends RuntimeException {
  private final UUID orderId;

  public CheckoutSetupException(UUID orderId) {
    super("Payment setup could not be confirmed. Check this order before retrying.");
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
