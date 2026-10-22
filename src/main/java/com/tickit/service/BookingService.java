package com.tickit.service;

import com.tickit.dto.BookingResponse;
import com.tickit.dto.SeatStatusUpdate;
import com.tickit.exception.SeatAlreadyBookedException;
import com.tickit.exception.SeatHoldExpiredException;
import com.tickit.model.*;
import com.tickit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Core booking service. Orchestrates:
 *  1. Redis hold validation
 *  2. PostgreSQL insert with optimistic concurrency
 *  3. WebSocket broadcast
 *  4. Async email trigger
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
                    "No active hold for seat " + seatId + " on event " + eventId +
                    ". Please hold the seat first.");
        }

        // 2. Check for existing booking (belt-and-suspenders with DB unique constraint)
        if (bookingRepo.existsBySeatIdAndEventId(seatId, eventId)) {
            seatHoldService.releaseHold(eventId, seatId);
            throw new SeatAlreadyBookedException(
                    "Seat " + seatId + " is already booked for event " + eventId);
        }

        // 3. Load entities
        Seat seat = seatRepo.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("Seat not found: " + seatId));
        Event event = eventRepo.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 4. Persist booking (OCC via @Version)
        Booking booking = Booking.builder()
                .seat(seat)
                .event(event)
                .user(user)
                .bookedAt(LocalDateTime.now())
                .status("CONFIRMED")
                .build();
        booking = bookingRepo.save(booking);

        // 5. Release the Redis hold
        seatHoldService.releaseHold(eventId, seatId);

        // 6. Broadcast seat status via WebSocket
        String seatLabel = seat.getSeatRow() + seat.getSeatNumber();
        SeatStatusUpdate update = SeatStatusUpdate.builder()
                .eventId(eventId)
                .seatId(seatId)
                .seatLabel(seatLabel)
                .status("BOOKED")
                .build();
        messagingTemplate.convertAndSend("/topic/seats/" + eventId, update);
        log.info("Booking confirmed: id={}, event={}, seat={}, user={}",
                booking.getId(), eventId, seatId, userId);

        // 7. Send confirmation email (async)
        emailService.sendBookingConfirmation(user.getEmail(), user.getFullName(),
                event.getName(), seatLabel, booking.getId());

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
