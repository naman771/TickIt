package com.tickit;

import com.tickit.service.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SeatHoldServiceTest {

    @Autowired
    private SeatHoldService seatHoldService;

    private final Long eventId = 9999L;
    private final Long seatId = 8888L;
    private final Long user1 = 1L;
    private final Long user2 = 2L;

    @BeforeEach
    void setUp() {
        seatHoldService.releaseHold(eventId, seatId);
    }

    @Test
    @DisplayName("User 1 acquires hold successfully; User 2 is rejected")
    void shouldAcquireAndBlockCompetitor() {
        // User 1 acquires hold
        boolean acquired = seatHoldService.acquireHold(eventId, seatId, user1);
        assertTrue(acquired, "First user should acquire hold");
        assertTrue(seatHoldService.isSeatHeld(eventId, seatId), "Seat should report held");
        assertTrue(seatHoldService.isHoldValid(eventId, seatId, user1), "Hold should be valid for User 1");

        // User 2 tries to acquire the same seat
        boolean secondAcquired = seatHoldService.acquireHold(eventId, seatId, user2);
        assertFalse(secondAcquired, "Second user must be rejected while hold is active");
        assertFalse(seatHoldService.isHoldValid(eventId, seatId, user2), "Hold must not be valid for User 2");
    }

    @Test
    @DisplayName("Hold acquisition is idempotent for the same user")
    void shouldBeIdempotentForSameUser() {
        assertTrue(seatHoldService.acquireHold(eventId, seatId, user1));
        // Same user acquires again (e.g. page refresh)
        assertTrue(seatHoldService.acquireHold(eventId, seatId, user1), "Same user should retain hold");
    }

    @Test
    @DisplayName("Releasing hold allows another user to acquire")
    void shouldAllowAcquisitionAfterRelease() {
        seatHoldService.acquireHold(eventId, seatId, user1);
        seatHoldService.releaseHold(eventId, seatId);

        assertFalse(seatHoldService.isSeatHeld(eventId, seatId), "Seat should be free after release");
        boolean user2Acquired = seatHoldService.acquireHold(eventId, seatId, user2);
        assertTrue(user2Acquired, "User 2 should acquire seat once released");
    }
}
