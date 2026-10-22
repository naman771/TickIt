package com.tickit.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingRequest {
    @NotNull(message = "eventId is required")
    private Long eventId;

    @NotNull(message = "seatId is required")
    private Long seatId;

    private Long userId;
}
