package com.tickit.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EventDto {
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
