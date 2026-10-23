package com.tickit.repository;

import com.tickit.model.Seat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
  List<Seat> findByVenueId(Long venueId);

  @org.springframework.data.jpa.repository.Query(
      "select s from Seat s where s.venue.event.id=:eventId and s.retired=false order by s.id")
  List<Seat> findByVenueEventId(
      @org.springframework.data.repository.query.Param("eventId") Long eventId);
}
