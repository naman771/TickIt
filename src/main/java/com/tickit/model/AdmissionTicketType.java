package com.tickit.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "admission_ticket_type")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionTicketType {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "pool_id", nullable = false)
  private AdmissionPool pool;

  @Column(nullable = false, length = 80)
  private String name;

  private BigDecimal price;
  private int quota;
  private int sold;
}
