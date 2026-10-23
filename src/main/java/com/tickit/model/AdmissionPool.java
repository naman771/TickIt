package com.tickit.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "admission_pool")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionPool {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @Column(nullable = false, length = 80)
  private String name;

  private int capacity;
  private int sold;
}
