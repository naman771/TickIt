package com.tickit.repository;

import com.tickit.model.Booking;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT (SELECT count(*) FROM booking WHERE user_id=:user AND event_id=:event) +"
              + " COALESCE((SELECT sum(i.quantity) FROM checkout_item i JOIN checkout_order o ON"
              + " o.id=i.order_id WHERE o.user_id=:user AND o.event_id=:event AND"
              + " o.status='CONFIRMED' AND i.ticket_type_id IS NOT NULL),0)",
      nativeQuery = true)
  long countAllTickets(
      @org.springframework.data.repository.query.Param("user") Long user,
      @org.springframework.data.repository.query.Param("event") Long event);

  long countByUserIdAndEventId(Long userId, Long eventId);

  List<Booking> findByEventId(Long eventId);

  List<Booking> findByUserId(Long userId);

  Optional<Booking> findBySeatIdAndEventId(Long seatId, Long eventId);

  boolean existsBySeatIdAndEventId(Long seatId, Long eventId);
}
