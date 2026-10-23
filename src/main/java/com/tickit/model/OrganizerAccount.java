package com.tickit.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "organizer_account")
@Getter
@Setter
@NoArgsConstructor
public class OrganizerAccount {
  @Id
  @Column(name = "user_id")
  private Long id;

  @OneToOne
  @MapsId
  @JoinColumn(name = "user_id")
  private AppUser user;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
}
