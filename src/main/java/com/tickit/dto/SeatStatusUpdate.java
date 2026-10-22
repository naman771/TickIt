package com.tickit.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SeatStatusUpdate {
    private Long eventId;
    private Long seatId;
    private String seatLabel;
    private String status;   // FREE, HELD, BOOKED
    private Long heldByUserId;
}
