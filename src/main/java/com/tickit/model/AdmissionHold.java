package com.tickit.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "admission_hold")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionHold {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ticket_type_id", nullable = false)
  private AdmissionTicketType ticketType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  private int quantity;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  private boolean released;
}
