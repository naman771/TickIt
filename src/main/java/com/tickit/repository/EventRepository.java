package com.tickit.repository;

import com.tickit.model.Event;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {
  Optional<Event> findBySlugAndPublicationStatus(String slug, String status);

  boolean existsBySlugAndPublicationStatus(String slug, String status);

  boolean existsByIdAndPublicationStatus(Long id, String status);

  List<Event> findByPublicationStatusOrderByStartTimeAsc(String status);

  @Query(
      value =
          """
          SELECT e.* FROM event e
          WHERE e.publication_status='PUBLISHED'
            AND (COALESCE(e.end_time,e.start_time+interval '8 hours')
                 AT TIME ZONE e.timezone) > :now
          ORDER BY (e.start_time AT TIME ZONE e.timezone),e.id
          """,
      nativeQuery = true)
  List<Event> findDiscoverable(@Param("now") java.time.Instant now);

  List<Event> findByOrganizerIdOrderByCreatedAtDesc(Long ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Event e where e.id=:id")
  Optional<Event> lockById(@Param("id") Long id);

  List<Event> findByStartTimeAfterOrderByStartTimeAsc(LocalDateTime after);
}
