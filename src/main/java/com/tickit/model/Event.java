package com.tickit.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

@Entity
@Table(name = "event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 512)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "start_time", nullable = false)
  private LocalDateTime startTime;

  @Column(name = "end_time")
  private LocalDateTime endTime;

  @Column(name = "image_url", length = 1024)
  private String imageUrl;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Builder.Default
  @Column(name = "updated_at", nullable = false)
  private java.time.Instant updatedAt = java.time.Instant.now();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "organizer_id")
  private OrganizerAccount organizer;

  @Builder.Default
  @Column(name = "publication_status", nullable = false)
  private String publicationStatus = "PUBLISHED";

  @Builder.Default
  @Column(name = "admission_mode", nullable = false)
  private String admissionMode = "SEATED";

  @Builder.Default
  @Column(nullable = false)
  private String category = "MUSIC";

  @Builder.Default
  @Column(nullable = false)
  private String city = "";

  @Builder.Default
  @Column(nullable = false)
  private String timezone = "Asia/Kolkata";

  @Builder.Default
  @Column(nullable = false)
  private String currency = "USD";

  @Column(name = "sales_start")
  private LocalDateTime salesStart;

  @Column(name = "sales_end")
  private LocalDateTime salesEnd;

  @Builder.Default
  @Column(name = "per_order_limit", nullable = false)
  private int perOrderLimit = 3;

  @Column(name = "minimum_age")
  private Integer minimumAge;

  @Column(name = "guest_capacity", nullable = false)
  private int guestCapacity;

  @Builder.Default
  @Column(name = "stage_label", nullable = false)
  private String stageLabel = "Stage";

  @Builder.Default
  @Column(name = "stage_x", nullable = false)
  private int stageX = 400;

  @Builder.Default
  @Column(name = "stage_y", nullable = false)
  private int stageY = 20;

  @Column(name = "change_note", length = 1000)
  private String changeNote;

  @Column(name = "schedule_changed_at")
  private java.time.Instant scheduleChangedAt;

  @Version
  @Column(name = "layout_version", nullable = false)
  private Long layoutVersion;

  @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
  @Builder.Default
  private List<Venue> venues = new ArrayList<>();

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = LocalDateTime.now();
  }
}
