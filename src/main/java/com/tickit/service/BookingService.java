package com.tickit.service;

import com.tickit.dto.BookingResponse;
import com.tickit.dto.SeatStatusUpdate;
import com.tickit.exception.SeatAlreadyBookedException;
import com.tickit.exception.SeatHoldExpiredException;
import com.tickit.model.*;
import com.tickit.repository.*;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Core booking service. Orchestrates: 1. Redis hold validation 2. PostgreSQL insert protected by a
 * unique constraint 3. Post-commit WebSocket broadcast 4. Post-commit async email trigger
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

  private final BookingRepository bookingRepo;
  private final SeatRepository seatRepo;
  private final EventRepository eventRepo;
  private final UserRepository userRepo;
  private final SeatHoldService seatHoldService;
  private final SimpMessagingTemplate messagingTemplate;
  private final EmailService emailService;

  @Transactional
  public BookingResponse createBooking(Long eventId, Long seatId, Long userId) {
    // 1. Validate hold
    if (!seatHoldService.isHoldValid(eventId, seatId, userId)) {
      throw new SeatHoldExpiredException(
          "No active hold for seat "
              + seatId
              + " on event "
              + eventId
              + ". Please hold the seat first.");
    }

    // 2. Check for existing booking (belt-and-suspenders with DB unique constraint)
    if (bookingRepo.existsBySeatIdAndEventId(seatId, eventId)) {
      throw new SeatAlreadyBookedException(
          "Seat " + seatId + " is already booked for event " + eventId);
    }

    // 3. Load entities
    Seat seat =
        seatRepo
            .findById(seatId)
            .orElseThrow(() -> new IllegalArgumentException("Seat not found: " + seatId));
    Event event =
        eventRepo
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    if (!"PUBLISHED".equals(event.getPublicationStatus())) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    if (!seat.getVenue().getEvent().getId().equals(eventId)) {
      throw new IllegalArgumentException("Seat does not belong to this event");
    }

    // The unique (seat_id, event_id) constraint arbitrates competing inserts.
    Booking booking =
        Booking.builder()
            .seat(seat)
            .event(event)
            .user(user)
            .bookedAt(LocalDateTime.now())
            .status("CONFIRMED")
            .build();
    booking = bookingRepo.saveAndFlush(booking);

    String seatLabel = seat.displayLabel();
    Long bookingId = booking.getId();
    SeatStatusUpdate update =
        SeatStatusUpdate.builder()
            .eventId(eventId)
            .seatId(seatId)
            .seatLabel(seatLabel)
            .status("BOOKED")
            .build();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            // A notification outage must never turn a committed booking into an HTTP failure.
            try {
              seatHoldService.releaseHold(eventId, seatId, userId);
            } catch (Exception ex) {
              log.warn("Hold release failed for booking {}", bookingId, ex);
            }
            try {
              messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);
            } catch (Exception ex) {
              log.warn("Seat broadcast failed for booking {}", bookingId, ex);
            }
            try {
              emailService.sendBookingConfirmation(
                  user.getEmail(), user.getFullName(), event.getName(), seatLabel, bookingId);
            } catch (Exception ex) {
              log.warn("Email dispatch failed for booking {}", bookingId, ex);
            }
          }
        });

    return BookingResponse.builder()
        .bookingId(booking.getId())
        .eventId(eventId)
        .seatId(seatId)
        .seatLabel(seatLabel)
        .status(booking.getStatus())
        .bookedAt(booking.getBookedAt())
        .build();
  }
}
