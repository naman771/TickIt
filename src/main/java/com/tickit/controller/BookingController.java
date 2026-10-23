package com.tickit.controller;

import com.tickit.dto.BookingRequest;
import com.tickit.dto.BookingResponse;
import com.tickit.dto.SeatStatusUpdate;
import com.tickit.exception.SeatAlreadyBookedException;
import com.tickit.model.Booking;
import com.tickit.model.Seat;
import com.tickit.repository.BookingRepository;
import com.tickit.repository.SeatRepository;
import com.tickit.service.BookingService;
import com.tickit.service.SeatHoldService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

  private final BookingService bookingService;
  private final com.tickit.repository.EventRepository events;
  private final com.tickit.repository.AdmissionHoldRepository admissionHolds;
  private final com.tickit.service.CurrentUserService currentUser;
  private final com.tickit.config.AuthSettings authSettings;
  private final SeatHoldService seatHoldService;
  private final SeatRepository seatRepository;
  private final BookingRepository bookingRepository;
  private final SimpMessagingTemplate messagingTemplate;
  private final com.tickit.repository.UserRepository userRepository;

  /**
   * Step 1 of Booking flow: Acquire temporary short-TTL hold on a seat. Protected by Redis Token
   * Bucket rate limiter.
   */
  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/holds")
  public ResponseEntity<?> holdSeat(@Valid @RequestBody BookingRequest request) {
    Long eventId = request.getEventId();
    Long seatId = request.getSeatId();
    Long userId = currentUser.resolve(request.getUserId()).getId();

    events.lockById(eventId).orElseThrow(() -> new IllegalArgumentException("Event not found"));
    Seat seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new IllegalArgumentException("Seat not found"));
    if (!seat.getVenue().getEvent().getId().equals(eventId)) {
      throw new IllegalArgumentException("Seat does not belong to this event");
    }
    if (!"PUBLISHED".equals(seat.getVenue().getEvent().getPublicationStatus())) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.NOT_FOUND, "Event not found");
    }
    com.tickit.service.EventSales.requireOpen(seat.getVenue().getEvent());
    com.tickit.service.EventSales.requireAge(seat.getVenue().getEvent(), request.getAttendeeAge());
    if (!userRepository.existsById(userId)) throw new IllegalArgumentException("User not found");

    // 1. Check if already permanently booked in database
    if (bookingRepository.existsBySeatIdAndEventId(seatId, eventId)) {
      throw new SeatAlreadyBookedException("Seat " + seatId + " is already booked for this event.");
    }

    // 2. Try to acquire Redis hold
    int booked = (int) bookingRepository.countByUserIdAndEventId(userId, eventId);
    int limit = Math.min(3, seat.getVenue().getEvent().getPerOrderLimit());
    boolean acquired =
        seatHoldService.acquireHold(
            eventId,
            seatId,
            userId,
            limit - booked - (int) admissionHolds.activeForUser(userId, eventId));
    if (!acquired) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              Map.of(
                  "status",
                  409,
                  "error",
                  "Seat unavailable or your per-event reservation limit has been reached (maximum 3"
                      + " seats)."));
    }

    // 3. Broadcast real-time hold update to all connected WebSocket clients
    String label = (seat != null) ? (seat.displayLabel()) : String.valueOf(seatId);

    SeatStatusUpdate update =
        SeatStatusUpdate.builder()
            .eventId(eventId)
            .seatId(seatId)
            .seatLabel(label)
            .status("HELD")
            .heldByUserId(userId)
            .build();
    messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);

    return ResponseEntity.ok(
        Map.of(
            "status",
            "HELD",
            "eventId",
            eventId,
            "seatId",
            seatId,
            "seatLabel",
            label,
            "expiresInSeconds",
            seatHoldService.remainingSeconds(eventId, seatId),
            "message",
            "Seat held successfully. Complete booking before the hold expires."));
  }

  /** Release hold if user cancels or navigates away. */
  @DeleteMapping("/holds/{eventId}/{seatId}")
  public ResponseEntity<?> releaseHold(
      @PathVariable Long eventId,
      @PathVariable Long seatId,
      @RequestParam(required = false) Long userId) {
    userId = currentUser.resolve(userId).getId();
    if (!seatHoldService.releaseHold(eventId, seatId, userId)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "No hold owned by this user"));
    }

    Seat seat = seatRepository.findById(seatId).orElse(null);
    String label = (seat != null) ? (seat.displayLabel()) : String.valueOf(seatId);

    SeatStatusUpdate update =
        SeatStatusUpdate.builder()
            .eventId(eventId)
            .seatId(seatId)
            .seatLabel(label)
            .status(bookingRepository.existsBySeatIdAndEventId(seatId, eventId) ? "BOOKED" : "FREE")
            .build();
    messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);

    return ResponseEntity.ok(Map.of("message", "Hold released successfully"));
  }

  /**
   * Step 2 of Booking flow: Commit the booking with database uniqueness and Redis verification.
   * Protected by Redis Token Bucket rate limiter.
   */
  @PostMapping("/bookings")
  public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody BookingRequest request) {
    Long userId = currentUser.resolve(request.getUserId()).getId();
    if (!authSettings.isTest()) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Checkout is temporarily unavailable");
    }
    BookingResponse response =
        bookingService.createBooking(request.getEventId(), request.getSeatId(), userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /** Get booking history for a user. */
  @GetMapping("/bookings/user/{userId}")
  public ResponseEntity<List<BookingResponse>> getUserBookings(@PathVariable Long userId) {
    userId = currentUser.resolve(userId).getId();
    return bookingsForUser(userId);
  }

  @GetMapping("/bookings/me")
  public ResponseEntity<List<BookingResponse>> getMyBookings() {
    return bookingsForUser(currentUser.resolve(null).getId());
  }

  private ResponseEntity<List<BookingResponse>> bookingsForUser(Long userId) {
    List<Booking> bookings = bookingRepository.findByUserId(userId);
    List<BookingResponse> responses =
        bookings.stream()
            .map(
                b ->
                    BookingResponse.builder()
                        .bookingId(b.getId())
                        .eventId(b.getEvent().getId())
                        .seatId(b.getSeat().getId())
                        .seatLabel(b.getSeat().displayLabel())
                        .status(b.getStatus())
                        .bookedAt(b.getBookedAt())
                        .build())
            .collect(Collectors.toList());

    return ResponseEntity.ok(responses);
  }
}
