package com.tickit.repository;

import com.tickit.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByEventId(Long eventId);
    List<Booking> findByUserId(Long userId);
    Optional<Booking> findBySeatIdAndEventId(Long seatId, Long eventId);
    boolean existsBySeatIdAndEventId(Long seatId, Long eventId);
}
