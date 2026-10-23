package com.tickit.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "seat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "seat_row", nullable = false, length = 10)
  private String seatRow;

  @Column(name = "seat_number", nullable = false)
  private int seatNumber;

  @Column(nullable = false)
  private BigDecimal price;

  @Builder.Default
  @Column(name = "section_code", nullable = false, length = 40)
  private String section = "Main";

  @Builder.Default private boolean retired = false;

  @Column(name = "map_x")
  private Integer x;

  @Column(name = "map_y")
  private Integer y;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  public String displayLabel() {
    return section + " · " + seatRow + seatNumber;
  }
}
