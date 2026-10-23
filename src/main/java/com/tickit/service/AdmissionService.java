package com.tickit.service;

import com.tickit.model.*;
import com.tickit.repository.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdmissionService {
  private final EventRepository events;
  private final AdmissionPoolRepository pools;
  private final AdmissionTicketTypeRepository tickets;
  private final AdmissionHoldRepository holds;
  private final CurrentUserService currentUser;
  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true)
  public List<Map<String, Object>> availability(Long eventId) {
    Event e = published(eventId);
    return pools.findByEventIdOrderById(eventId).stream()
        .map(
            p -> {
              long remaining =
                  Math.max(0, p.getCapacity() - p.getSold() - holds.activeInPool(p.getId()));
              Map<String, Object> data = new LinkedHashMap<>();
              data.put("id", p.getId());
              data.put("name", p.getName());
              data.put("capacity", p.getCapacity());
              data.put("available", remaining);
              data.put(
                  "tickets",
                  tickets.findByPoolIdOrderById(p.getId()).stream()
                      .map(
                          t ->
                              Map.of(
                                  "id",
                                  t.getId(),
                                  "name",
                                  t.getName(),
                                  "price",
                                  t.getPrice(),
                                  "currency",
                                  e.getCurrency(),
                                  "available",
                                  Math.min(
                                      remaining,
                                      Math.max(
                                          0,
                                          t.getQuota()
                                              - t.getSold()
                                              - holds.activeForTicket(t.getId())))))
                      .toList());
              return data;
            })
        .toList();
  }

  @Transactional
  public Map<String, Object> hold(Long eventId, Long ticketId, int quantity) {
    AppUser user = currentUser.resolve(null);
    Event e =
        events
            .lockById(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!"PUBLISHED".equals(e.getPublicationStatus()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (quantity < 1 || quantity > e.getPerOrderLimit())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose a quantity within this event's per-order limit");
    EventSales.requireOpen(e);
    AdmissionTicketType type =
        tickets
            .findById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!type.getPool().getEvent().getId().equals(eventId))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Ticket does not belong to this event");
    AdmissionPool pool = type.getPool();
    if (holds.activeForUser(user.getId(), eventId) + quantity > e.getPerOrderLimit())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The per-account reservation limit for this event has been reached");
    // The event row serializes competing reservations across all ticket tiers in a shared pool.
    if (pool.getCapacity() - pool.getSold() - holds.activeInPool(pool.getId()) < quantity
        || type.getQuota() - type.getSold() - holds.activeForTicket(ticketId) < quantity)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "That quantity is no longer available");
    Instant now =
        Objects.requireNonNull(
                jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class))
            .toInstant();
    AdmissionHold hold = new AdmissionHold();
    hold.setId(UUID.randomUUID());
    hold.setEvent(e);
    hold.setUser(user);
    hold.setTicketType(type);
    hold.setQuantity(quantity);
    hold.setExpiresAt(now.plusSeconds(120));
    holds.saveAndFlush(hold);
    return Map.of(
        "id",
        hold.getId(),
        "quantity",
        quantity,
        "ticketName",
        type.getName(),
        "zone",
        pool.getName(),
        "expiresAt",
        hold.getExpiresAt(),
        "total",
        type.getPrice().multiply(java.math.BigDecimal.valueOf(quantity)),
        "currency",
        e.getCurrency());
  }

  @Transactional
  public void release(UUID id) {
    AppUser user = currentUser.resolve(null);
    AdmissionHold h =
        holds.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!h.getUser().getId().equals(user.getId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    events.lockById(h.getEvent().getId());
    h.setReleased(true);
    holds.save(h);
  }

  private Event published(Long id) {
    return events
        .findById(id)
        .filter(e -> "PUBLISHED".equals(e.getPublicationStatus()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void cleanup() {
    holds.pruneExpired();
  }
}
