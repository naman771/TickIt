package com.tickit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrganizerEventRequest(
    @NotBlank @Size(max = 512) String name,
    @Size(max = 10000) String description,
    @NotBlank @Pattern(regexp = "MUSIC|SPORTS|COMEDY|THEATRE|CONFERENCE|COMMUNITY|OTHER")
        String category,
    @NotBlank @Pattern(regexp = "SEATED|GENERAL|MIXED") String admissionMode,
    @NotBlank @Size(max = 255) String venueName,
    @NotBlank @Size(max = 512) String address,
    @NotBlank @Size(max = 100) String city,
    @NotNull LocalDateTime startTime,
    LocalDateTime endTime,
    @NotBlank @Size(max = 64) String timezone,
    @Min(1) @Max(100000) int guestCapacity,
    @Size(max = 1024) String imageUrl,
    @Size(max = 80) String stageLabel,
    @Min(0) @Max(800) int stageX,
    @Min(0) @Max(650) int stageY,
    Long version,
    @NotNull @Size(max = 1500) List<@Valid SeatInput> seats,
    @NotNull @Size(max = 12) List<@Valid PoolInput> pools,
    LocalDateTime salesStart,
    LocalDateTime salesEnd,
    @Min(1) @Max(3) int perOrderLimit,
    @Min(1) @Max(120) Integer minimumAge) {
  public record SeatInput(
      @NotBlank @Size(max = 40) String section,
      @NotBlank @Size(max = 10) String row,
      @Min(1) @Max(999) int number,
      @Min(0) @Max(970) int x,
      @Min(0) @Max(670) int y,
      @NotNull @DecimalMin("0.00") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
          BigDecimal price) {}

  public record PoolInput(
      @NotBlank @Size(max = 80) String name,
      @Min(1) @Max(100000) int capacity,
      @NotNull @Size(min = 1, max = 8) List<@Valid TicketInput> tickets) {}

  public record TicketInput(
      @NotBlank @Size(max = 80) String name,
      @NotNull @DecimalMin("0.00") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
          BigDecimal price,
      @Min(1) @Max(100000) int quota) {}
}
