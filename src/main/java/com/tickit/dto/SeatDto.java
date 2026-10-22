package com.tickit.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SeatDto {
    @lombok.Builder.Default
    private String currency = "USD";
    private Long seatId;
    private String row;
    private int number;
    private String label;
    private BigDecimal price;
    private String status;   // FREE, HELD, BOOKED
}
