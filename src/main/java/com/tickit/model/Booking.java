package com.tickit.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking", uniqueConstraints = @UniqueConstraint(columnNames = {"seat_id", "event_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "booked_at", nullable = false)
    private LocalDateTime bookedAt;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "CONFIRMED";

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (bookedAt == null) bookedAt = LocalDateTime.now();
    }
}
