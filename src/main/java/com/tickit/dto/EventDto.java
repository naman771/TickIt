package com.tickit.dto;

import java.time.LocalDateTime;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventDto {
  private int capacity;
  private int availableTickets;
  private int perOrderLimit;
  private String currency;
  private String category;
  private String timezone;
  private String city;
  private String admissionMode;
  private java.math.BigDecimal fromPrice;
  private java.time.Instant startInstant;
  private String stageLabel;
  private int stageX;
  private int stageY;
  private int guestCapacity;
  private Long id;
  private String name;
  private String description;
  private LocalDateTime startTime;
  private LocalDateTime endTime;
  private String imageUrl;
  private String venueName;
  private String venueLocation;
  private int totalSeats;
  private int availableSeats;
}
