package com.tickit;

import com.tickit.dto.BookingResponse;
import com.tickit.model.AppUser;
import com.tickit.model.Event;
import com.tickit.model.Seat;
import com.tickit.model.Venue;
import com.tickit.repository.*;
import com.tickit.service.BookingService;
import com.tickit.service.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BookingConcurrencyTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private UserRepository userRepository;

    private Event testEvent;
    private Seat testSeat;
    private final List<AppUser> testUsers = new ArrayList<>();
    private final int NUM_THREADS = 50;

    @BeforeEach
    void setUp() {
        // Create isolated test event, venue, and seat
        testEvent = eventRepository.save(Event.builder()
                .name("Concurrency Stress Event")
                .description("Contention stress test")
                .startTime(LocalDateTime.now().plusDays(5))
                .build());

        Venue testVenue = venueRepository.save(Venue.builder()
                .name("Stress Test Arena")
                .event(testEvent)
                .build());

        testSeat = seatRepository.save(Seat.builder()
                .venue(testVenue)
                .seatRow("Z")
                .seatNumber(99)
                .price(new BigDecimal("150.00"))
                .build());

        // Ensure 50 unique users exist for concurrent requests
        testUsers.clear();
        for (int i = 1; i <= NUM_THREADS; i++) {
            final int idx = i;
            AppUser u = userRepository.findByEmail("stress_user_" + idx + "@test.local")
                    .orElseGet(() -> userRepository.save(AppUser.builder()
                            .email("stress_user_" + idx + "@test.local")
                            .fullName("Stress User " + idx)
                            .passwordHash("hashed")
                            .build()));
            testUsers.add(u);
        }

        // Clear any leftover hold in Redis
        seatHoldService.releaseHold(testEvent.getId(), testSeat.getId());
    }

    @Test
    @DisplayName("50 Concurrent Threads Competing for 1 Seat: Exactly 1 Booking, 0 Double-Bookings")
    void shouldPreventDoubleBookingUnderExtremeConcurrency() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch readyLatch = new CountDownLatch(NUM_THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);

        AtomicInteger successfulBookings = new AtomicInteger(0);
        AtomicInteger rejectedAttempts = new AtomicInteger(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final AppUser user = testUsers.get(i);
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    // Wait for all threads to be ready so they hit the server at the exact same millisecond
                    startGun.await();

                    // Step 1: Try to acquire Redis hold
                    boolean holdAcquired = seatHoldService.acquireHold(
                            testEvent.getId(),
                            testSeat.getId(),
                            user.getId()
                    );

                    if (holdAcquired) {
                        // Step 2: Commit booking in database (OCC + Unique constraint)
                        BookingResponse response = bookingService.createBooking(
                                testEvent.getId(),
                                testSeat.getId(),
                                user.getId()
                        );
                        if (response != null && "CONFIRMED".equals(response.getStatus())) {
                            successfulBookings.incrementAndGet();
                        }
                    } else {
                        rejectedAttempts.incrementAndGet();
                    }
                } catch (Exception ex) {
                    // Any failure (e.g., OCC conflict, expired hold, already booked) counts as rejected
                    rejectedAttempts.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait for all threads to align at the starting gate
        readyLatch.await(5, TimeUnit.SECONDS);

        // Fire the gun! All 50 threads execute at once
        startGun.countDown();

        // Wait for all threads to finish
        boolean finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(finishedInTime, "All threads should complete within 10 seconds");

        executor.shutdown();

        // ── Critical Invariants Verification ───────────────────────
        System.out.println("==================================================");
        System.out.println("CONCURRENCY TEST RESULTS:");
        System.out.println("Total Competing Threads: " + NUM_THREADS);
        System.out.println("Successful Bookings:     " + successfulBookings.get());
        System.out.println("Rejected Attempts:       " + rejectedAttempts.get());
        System.out.println("==================================================");

        // Invariant 1: Exactly one booking succeeded
        assertEquals(1, successfulBookings.get(), 
                "CRITICAL: Exactly ONE thread must succeed in booking the contested seat!");

        // Invariant 2: Exactly 49 attempts were rejected
        assertEquals(NUM_THREADS - 1, rejectedAttempts.get(), 
                "CRITICAL: All other " + (NUM_THREADS - 1) + " competing threads must be rejected!");

        // Invariant 3: Database truth check
        long dbBookingCount = bookingRepository.findAll().stream()
                .filter(b -> b.getEvent().getId().equals(testEvent.getId()) &&
                             b.getSeat().getId().equals(testSeat.getId()))
                .count();

        assertEquals(1, dbBookingCount, 
                "CRITICAL DATABASE CHECK: The database must contain exactly 1 booking row for this seat!");
    }
}
