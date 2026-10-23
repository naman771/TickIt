package com.tickit.repository;

import com.tickit.model.Booking;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  long countByUserIdAndEventId(Long userId, Long eventId);

  List<Booking> findByEventId(Long eventId);

  List<Booking> findByUserId(Long userId);

  Optional<Booking> findBySeatIdAndEventId(Long seatId, Long eventId);

  boolean existsBySeatIdAndEventId(Long seatId, Long eventId);
}
