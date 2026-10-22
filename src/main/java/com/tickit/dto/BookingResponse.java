package com.tickit.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingResponse {
    private Long bookingId;
    private Long eventId;
    private Long seatId;
    private String seatLabel;
    private String status;
    private LocalDateTime bookedAt;
}
