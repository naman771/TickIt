package com.tickit.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Safe operational metadata only; never retain provider bodies, credentials or customer data. */
public class ProviderRequestException extends ResponseStatusException {
  private final int providerStatus;

  public ProviderRequestException(int status) {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Payment provider request failed. Check your order before retrying.");
    this.providerStatus = status;
  }

  public int providerStatus() {
    return providerStatus;
  }

  public boolean retryable() {
    return providerStatus == 408
        || providerStatus == 409
        || providerStatus == 425
        || providerStatus == 429
        || providerStatus >= 500;
  }

  public String operationalMessage() {
    return "Provider HTTP "
        + providerStatus
        + (retryable() ? "; retry scheduled" : "; review required");
  }
}
