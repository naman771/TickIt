package com.tickit.repository;

import com.tickit.model.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    List<Venue> findByEventId(Long eventId);
}
