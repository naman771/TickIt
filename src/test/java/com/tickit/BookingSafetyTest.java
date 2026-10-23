package com.tickit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tickit.model.*;
import com.tickit.repository.*;
import com.tickit.service.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class BookingSafetyTest {
  @Autowired EventRepository events;
  @Autowired VenueRepository venues;
  @Autowired SeatRepository seats;
  @Autowired UserRepository users;
  @Autowired BookingRepository bookings;
  @Autowired BookingService bookingService;
  @Autowired SeatHoldService holds;
  @Autowired HoldCleanupService cleanup;
  @Autowired StringRedisTemplate redis;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean SimpMessagingTemplate messaging;
  @MockitoBean EmailService email;
  Event event;
  Seat seat;
  AppUser user;

  @BeforeEach
  void fixture() {
    event =
        events.save(
            Event.builder().name("Safety test").startTime(LocalDateTime.now().plusDays(2)).build());
    Venue venue = venues.save(Venue.builder().name("Safety venue").event(event).build());
    seat =
        seats.save(
            Seat.builder().venue(venue).seatRow("T").seatNumber(1).price(BigDecimal.TEN).build());
    user = users.findAll().getFirst();
    reset(messaging, email);
  }

  @AfterEach
  void release() {
    holds.releaseHold(event.getId(), seat.getId());
  }

  @Test
  void rollbackPreservesHoldAndDoesNotSendConfirmation() {
    holds.acquireHold(event.getId(), seat.getId(), user.getId());
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              bookingService.createBooking(event.getId(), seat.getId(), user.getId());
              verifyNoInteractions(email, messaging);
              tx.setRollbackOnly();
            });
    assertFalse(bookings.existsBySeatIdAndEventId(seat.getId(), event.getId()));
    assertTrue(holds.isHoldValid(event.getId(), seat.getId(), user.getId()));
    verifyNoInteractions(email, messaging);
  }

  @Test
  void committedBookingSurvivesNotificationFailure() {
    holds.acquireHold(event.getId(), seat.getId(), user.getId());
    doThrow(new IllegalStateException("broker unavailable"))
        .when(messaging)
        .convertAndSend(anyString(), any(Object.class));
    var response = bookingService.createBooking(event.getId(), seat.getId(), user.getId());
    assertEquals("CONFIRMED", response.getStatus());
    assertTrue(bookings.existsBySeatIdAndEventId(seat.getId(), event.getId()));
    assertFalse(holds.isSeatHeld(event.getId(), seat.getId()));
    verify(email)
        .sendBookingConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void anotherUserCannotReleaseHold() {
    holds.acquireHold(event.getId(), seat.getId(), user.getId());
    assertFalse(holds.releaseHold(event.getId(), seat.getId(), user.getId() + 10000));
    assertTrue(holds.isHoldValid(event.getId(), seat.getId(), user.getId()));
  }

  @Test
  void expirationIsRemovedFromIndexAndBroadcast() {
    holds.acquireHold(event.getId(), seat.getId(), user.getId());
    String key = "hold:" + event.getId() + ":" + seat.getId();
    redis.expire(key, Duration.ofMillis(30));
    redis.opsForZSet().add("hold:expiry", key, 0);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> !holds.isSeatHeld(event.getId(), seat.getId()));
    cleanup.releaseExpiredHolds();
    assertNull(redis.opsForZSet().score("hold:expiry", key));
    verify(messaging, atLeastOnce())
        .convertAndSend(
            eq("/topic/seats/" + event.getId()),
            argThat(
                (Object value) ->
                    value instanceof com.tickit.dto.SeatStatusUpdate update
                        && update.getSeatId().equals(seat.getId())
                        && "FREE".equals(update.getStatus())));
  }

  @Test
  void databaseRejectsCompetingInsertsEvenWithoutRedis() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> attempt =
        () -> {
          start.await();
          try {
            bookings.saveAndFlush(Booking.builder().event(event).seat(seat).user(user).build());
            return true;
          } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            return false;
          }
        };
    try {
      Future<Boolean> first = pool.submit(attempt), second = pool.submit(attempt);
      start.countDown();
      int success =
          (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
      assertEquals(1, success);
    } finally {
      pool.shutdownNow();
    }
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;

  @Test
  void holdAndReleaseRemainSuccessfulDuringBrokerFailure() throws Exception {
    doThrow(new IllegalStateException("broker offline"))
        .when(messaging)
        .convertAndSend(anyString(), any(Object.class));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/holds")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"eventId\":"
                        + event.getId()
                        + ",\"seatId\":"
                        + seat.getId()
                        + ",\"userId\":"
                        + user.getId()
                        + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertTrue(holds.isHoldValid(event.getId(), seat.getId(), user.getId()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/holds/" + event.getId() + "/" + seat.getId())
                .param("userId", user.getId().toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertFalse(holds.isSeatHeld(event.getId(), seat.getId()));
  }

  @Test
  void staleBookingVersionCannotOverwriteCommittedChanges() {
    var booking =
        bookings.saveAndFlush(Booking.builder().event(event).seat(seat).user(user).build());
    var first = bookings.findById(booking.getId()).orElseThrow();
    var stale = bookings.findById(booking.getId()).orElseThrow();
    first.setStatus("CHECKED_IN");
    bookings.saveAndFlush(first);
    stale.setStatus("CANCELLED");
    assertThrows(
        org.springframework.dao.OptimisticLockingFailureException.class,
        () -> bookings.saveAndFlush(stale));
    assertEquals("CHECKED_IN", bookings.findById(booking.getId()).orElseThrow().getStatus());
  }
}
