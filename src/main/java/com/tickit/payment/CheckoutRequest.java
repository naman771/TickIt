package com.tickit.payment;

import jakarta.validation.constraints.*;
import java.util.*;

public record CheckoutRequest(
    @NotNull @Positive Long eventId,
    @NotNull UUID idempotencyKey,
    @NotNull @Size(max = 3) List<@NotNull @Positive Long> seatIds,
    @Positive Long ticketTypeId,
    @Min(0) @Max(3) int quantity,
    @Min(0) @Max(120) Integer attendeeAge) {
  public String fingerprint() {
    return eventId
        + "|"
        + seatIds.stream().sorted().toList()
        + "|"
        + ticketTypeId
        + "|"
        + quantity
        + "|"
        + attendeeAge;
  }
}
