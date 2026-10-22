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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingService bookingService;
    private final SeatHoldService seatHoldService;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Step 1 of Booking flow: Acquire temporary short-TTL hold on a seat.
     * Protected by Redis Token Bucket rate limiter.
     */
    @PostMapping("/holds")
    public ResponseEntity<?> holdSeat(@Valid @RequestBody BookingRequest request) {
        Long eventId = request.getEventId();
        Long seatId = request.getSeatId();
        Long userId = request.getUserId();

        // 1. Check if already permanently booked in database
        if (bookingRepository.existsBySeatIdAndEventId(seatId, eventId)) {
            throw new SeatAlreadyBookedException("Seat " + seatId + " is already booked for this event.");
        }

        // 2. Try to acquire Redis hold
        boolean acquired = seatHoldService.acquireHold(eventId, seatId, userId);
        if (!acquired) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", 409,
                    "error", "Seat is currently held by another user. Please choose another seat or wait."
            ));
        }

        // 3. Broadcast real-time hold update to all connected WebSocket clients
        Seat seat = seatRepository.findById(seatId).orElse(null);
        String label = (seat != null) ? (seat.getSeatRow() + seat.getSeatNumber()) : String.valueOf(seatId);

        SeatStatusUpdate update = SeatStatusUpdate.builder()
                .eventId(eventId)
                .seatId(seatId)
                .seatLabel(label)
                .status("HELD")
                .heldByUserId(userId)
                .build();
        messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);

        return ResponseEntity.ok(Map.of(
                "status", "HELD",
                "eventId", eventId,
                "seatId", seatId,
                "seatLabel", label,
                "expiresInSeconds", 120,
                "message", "Seat held successfully for 120 seconds. Complete booking before timer expires."
        ));
    }

    /**
     * Release hold if user cancels or navigates away.
     */
    @DeleteMapping("/holds/{eventId}/{seatId}")
    public ResponseEntity<?> releaseHold(@PathVariable Long eventId,
                                         @PathVariable Long seatId,
                                         @RequestParam(required = false) Long userId) {
        seatHoldService.releaseHold(eventId, seatId);

        Seat seat = seatRepository.findById(seatId).orElse(null);
        String label = (seat != null) ? (seat.getSeatRow() + seat.getSeatNumber()) : String.valueOf(seatId);

        SeatStatusUpdate update = SeatStatusUpdate.builder()
                .eventId(eventId)
                .seatId(seatId)
                .seatLabel(label)
                .status("FREE")
                .build();
        messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);

        return ResponseEntity.ok(Map.of("message", "Hold released successfully"));
    }

    /**
     * Step 2 of Booking flow: Commit the booking with DB OCC and Redis verification.
     * Protected by Redis Token Bucket rate limiter.
     */
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody BookingRequest request) {
        BookingResponse response = bookingService.createBooking(
                request.getEventId(),
                request.getSeatId(),
                request.getUserId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get booking history for a user.
     */
    @GetMapping("/bookings/user/{userId}")
    public ResponseEntity<List<BookingResponse>> getUserBookings(@PathVariable Long userId) {
        List<Booking> bookings = bookingRepository.findByUserId(userId);
        List<BookingResponse> responses = bookings.stream().map(b -> BookingResponse.builder()
                .bookingId(b.getId())
                .eventId(b.getEvent().getId())
                .seatId(b.getSeat().getId())
                .seatLabel(b.getSeat().getSeatRow() + b.getSeat().getSeatNumber())
                .status(b.getStatus())
                .bookedAt(b.getBookedAt())
                .build()
        ).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }
}
