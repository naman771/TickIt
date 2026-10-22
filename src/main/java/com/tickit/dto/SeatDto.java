package com.tickit.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SeatDto {
    private Long seatId;
    private String row;
    private int number;
    private String label;
    private BigDecimal price;
    private String status;   // FREE, HELD, BOOKED
}
