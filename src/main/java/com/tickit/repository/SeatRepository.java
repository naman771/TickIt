package com.tickit.repository;

import com.tickit.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByVenueId(Long venueId);
    List<Seat> findByVenueEventId(Long eventId);
}
