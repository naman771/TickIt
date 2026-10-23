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
  List<Event> findByPublicationStatusOrderByStartTimeAsc(String status);

  List<Event> findByOrganizerIdOrderByCreatedAtDesc(Long ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Event e where e.id=:id")
  Optional<Event> lockById(@Param("id") Long id);

  List<Event> findByStartTimeAfterOrderByStartTimeAsc(LocalDateTime after);
}
