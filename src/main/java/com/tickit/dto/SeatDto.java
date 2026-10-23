package com.tickit.dto;

import java.math.BigDecimal;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatDto {
  @lombok.Builder.Default private String currency = "USD";
  private String section;
  private Integer x;
  private Integer y;
  private Long seatId;
  private String row;
  private int number;
  private String label;
  private BigDecimal price;
  private String status; // FREE, HELD, BOOKED
}
