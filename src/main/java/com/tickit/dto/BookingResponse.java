package com.tickit.dto;

import java.time.LocalDateTime;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {
  private com.tickit.service.EventSnapshots.Summary event;
  private Long bookingId;
  private Long eventId;
  private Long seatId;
  private String seatLabel;
  private String status;
  private LocalDateTime bookedAt;
}
