package com.tickit;

import static org.junit.jupiter.api.Assertions.*;

import com.tickit.service.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SeatHoldServiceTest {

  @Autowired private SeatHoldService seatHoldService;

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
    assertTrue(
        seatHoldService.isHoldValid(eventId, seatId, user1), "Hold should be valid for User 1");

    // User 2 tries to acquire the same seat
    boolean secondAcquired = seatHoldService.acquireHold(eventId, seatId, user2);
    assertFalse(secondAcquired, "Second user must be rejected while hold is active");
    assertFalse(
        seatHoldService.isHoldValid(eventId, seatId, user2), "Hold must not be valid for User 2");
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

  @Test
  void concurrentDifferentSeatRequestsCannotExceedThreePerAccount() throws Exception {
    long event = 700000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(12);
    var gun = new java.util.concurrent.CountDownLatch(1);
    var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
    try {
      for (long seat = 1; seat <= 12; seat++) {
        final long id = seat;
        results.add(
            workers.submit(
                () -> {
                  gun.await();
                  return seatHoldService.acquireHold(event, id, user1);
                }));
      }
      gun.countDown();
      int successes = 0;
      for (var result : results)
        if (result.get(5, java.util.concurrent.TimeUnit.SECONDS)) successes++;
      assertEquals(3, successes);
      assertEquals(3, seatHoldService.activeForUser(event, user1));
      long held =
          java.util.stream.LongStream.rangeClosed(1, 12)
              .filter(id -> seatHoldService.isHoldValid(event, id, user1))
              .findFirst()
              .orElseThrow();
      assertFalse(seatHoldService.releaseHold(event, held, user2));
      assertTrue(seatHoldService.releaseHold(event, held, user1));
      assertTrue(seatHoldService.acquireHold(event, 13L, user1));
      assertFalse(seatHoldService.acquireHold(event, 14L, user1));
    } finally {
      workers.shutdownNow();
      for (long seat = 1; seat <= 14; seat++) seatHoldService.releaseHold(event, seat);
    }
  }

  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

  @Test
  void naturalExpiryRestoresTheAccountAllowance() {
    var shortHolds = new SeatHoldService(redis);
    org.springframework.test.util.ReflectionTestUtils.setField(shortHolds, "holdTtlSeconds", 1);
    long event = 900000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
    try {
      for (long id = 1; id <= 3; id++) assertTrue(shortHolds.acquireHold(event, id, user1));
      assertFalse(shortHolds.acquireHold(event, 4L, user1));
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .until(() -> shortHolds.activeForUser(event, user1) == 0);
      assertTrue(shortHolds.acquireHold(event, 4L, user1));
    } finally {
      for (long id = 1; id <= 4; id++) shortHolds.releaseHold(event, id);
    }
  }
}
