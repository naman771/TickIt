package com.tickit.controller;

import com.tickit.dto.EventDto;
import com.tickit.dto.SeatDto;
import com.tickit.model.*;
import com.tickit.repository.BookingRepository;
import com.tickit.repository.EventRepository;
import com.tickit.repository.SeatRepository;
import com.tickit.repository.VenueRepository;
import com.tickit.service.SeatHoldService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventController {

  private final EventRepository eventRepository;
  private final VenueRepository venueRepository;
  private final SeatRepository seatRepository;
  private final BookingRepository bookingRepository;
  private final SeatHoldService seatHoldService;
  private final com.tickit.repository.AdmissionPoolRepository pools;
  private final com.tickit.repository.AdmissionTicketTypeRepository ticketTypes;
  private final com.tickit.repository.AdmissionHoldRepository admissionHolds;

  @GetMapping
  public ResponseEntity<List<EventDto>> getAllEvents() {
    List<Event> events = eventRepository.findByPublicationStatusOrderByStartTimeAsc("PUBLISHED");
    List<EventDto> dtos = events.stream().map(this::toEventDto).collect(Collectors.toList());
    return ResponseEntity.ok(dtos);
  }

  @GetMapping("/by-slug/{slug}")
  public ResponseEntity<EventDto> bySlug(@PathVariable String slug) {
    return eventRepository
        .findBySlugAndPublicationStatus(slug, "PUBLISHED")
        .map(this::toEventDto)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/{id}")
  public ResponseEntity<EventDto> getEventById(@PathVariable Long id) {
    return eventRepository
        .findById(id)
        .filter(e -> "PUBLISHED".equals(e.getPublicationStatus()))
        .map(this::toEventDto)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/{id}/seats")
  public ResponseEntity<List<SeatDto>> getEventSeats(@PathVariable Long id) {
    if (eventRepository
        .findById(id)
        .filter(e -> "PUBLISHED".equals(e.getPublicationStatus()))
        .isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    List<Seat> seats = seatRepository.findByVenueEventId(id);
    List<Booking> bookings = bookingRepository.findByEventId(id);
    Set<Long> bookedSeatIds =
        bookings.stream().map(b -> b.getSeat().getId()).collect(Collectors.toSet());

    var heldSeats = seatHoldService.heldSeatIds(id, seats.stream().map(Seat::getId).toList());
    List<SeatDto> seatDtos =
        seats.stream()
            .map(
                seat -> {
                  String status = "FREE";
                  if (bookedSeatIds.contains(seat.getId())) {
                    status = "BOOKED";
                  } else if (heldSeats.contains(seat.getId())) {
                    status = "HELD";
                  }

                  return SeatDto.builder()
                      .seatId(seat.getId())
                      .currency(seat.getVenue().getEvent().getCurrency())
                      .section(seat.getSection())
                      .x(seat.getX())
                      .y(seat.getY())
                      .row(seat.getSeatRow())
                      .number(seat.getSeatNumber())
                      .label(seat.displayLabel())
                      .price(seat.getPrice())
                      .status(status)
                      .build();
                })
            .sorted(Comparator.comparing(SeatDto::getRow).thenComparingInt(SeatDto::getNumber))
            .collect(Collectors.toList());

    return ResponseEntity.ok(seatDtos);
  }

  private EventDto toEventDto(Event event) {
    List<Seat> seats = seatRepository.findByVenueEventId(event.getId());
    List<Booking> bookings = bookingRepository.findByEventId(event.getId());

    var admissionPools = pools.findByEventIdOrderById(event.getId());
    int standing =
        admissionPools.stream().mapToInt(com.tickit.model.AdmissionPool::getCapacity).sum();
    Set<Long> bookedIds =
        bookings.stream().map(b -> b.getSeat().getId()).collect(Collectors.toSet());
    Set<Long> heldIds =
        seatHoldService.heldSeatIds(event.getId(), seats.stream().map(Seat::getId).toList());
    var freeSeats =
        seats.stream()
            .filter(s -> !bookedIds.contains(s.getId()) && !heldIds.contains(s.getId()))
            .toList();
    List<java.math.BigDecimal> prices =
        new ArrayList<>(freeSeats.stream().map(Seat::getPrice).toList());
    int standingAvailable = 0;
    for (var pool : admissionPools) {
      long poolRemaining =
          Math.max(
              0, pool.getCapacity() - pool.getSold() - admissionHolds.activeInPool(pool.getId()));
      long tierRemaining = 0;
      for (var type : ticketTypes.findByPoolIdOrderById(pool.getId())) {
        long remaining =
            Math.max(
                0, type.getQuota() - type.getSold() - admissionHolds.activeForTicket(type.getId()));
        tierRemaining += remaining;
        if (poolRemaining > 0 && remaining > 0) prices.add(type.getPrice());
      }
      standingAvailable += (int) Math.min(poolRemaining, tierRemaining);
    }
    java.math.BigDecimal fromPrice =
        prices.stream().min(java.math.BigDecimal::compareTo).orElse(null);
    int total = seats.size();
    int available = freeSeats.size();

    List<Venue> venues = venueRepository.findByEventId(event.getId());
    String venueName = venues.isEmpty() ? "Main Arena" : venues.get(0).getName();
    String venueLocation = venues.isEmpty() ? "Downtown" : venues.get(0).getLocation();

    return EventDto.builder()
        .id(event.getId())
        .version(event.getLayoutVersion())
        .status(event.getPublicationStatus())
        .changeNote(event.getChangeNote())
        .scheduleChanged(event.getScheduleChangedAt() != null)
        .perOrderLimit(Math.min(3, event.getPerOrderLimit()))
        .minimumAge(event.getMinimumAge())
        .cancellationCutoffHours(event.getCancellationCutoffHours())
        .currency(event.getCurrency())
        .category(event.getCategory())
        .city(event.getCity())
        .timezone(event.getTimezone())
        .admissionMode(event.getAdmissionMode())
        .fromPrice(fromPrice)
        .guestCapacity(event.getGuestCapacity())
        .stageLabel(event.getStageLabel())
        .stageX(event.getStageX())
        .stageY(event.getStageY())
        .startInstant(
            event.getStartTime().atZone(java.time.ZoneId.of(event.getTimezone())).toInstant())
        .name(event.getName())
        .slug(event.getSlug())
        .description(event.getDescription())
        .organizerName(event.getOrganizer() == null ? null : event.getOrganizer().getDisplayName())
        .information(
            new com.tickit.dto.OrganizerEventRequest.Information(
                event.getLanguages(),
                event.getArtists(),
                event.getEntryInstructions(),
                event.getAccessibilityInfo(),
                event.getTicketInclusions(),
                event.getFaq()))
        .endInstant(
            event.getEndTime() == null
                ? null
                : event.getEndTime().atZone(java.time.ZoneId.of(event.getTimezone())).toInstant())
        .startTime(event.getStartTime())
        .endTime(event.getEndTime())
        .imageUrl(event.getImageUrl())
        .venueName(venueName)
        .venueLocation(venueLocation)
        .capacity(total + standing)
        .availableTickets(available + standingAvailable)
        .totalSeats(total)
        .availableSeats(available)
        .build();
  }
}
