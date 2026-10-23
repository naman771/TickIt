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
    int standingAvailable =
        admissionPools.stream()
            .mapToInt(
                p ->
                    (int)
                        Math.max(
                            0,
                            p.getCapacity() - p.getSold() - admissionHolds.activeInPool(p.getId())))
            .sum();
    java.math.BigDecimal fromPrice =
        java.util.stream.Stream.concat(
                seats.stream().map(Seat::getPrice),
                admissionPools.stream()
                    .flatMap(p -> ticketTypes.findByPoolIdOrderById(p.getId()).stream())
                    .map(com.tickit.model.AdmissionTicketType::getPrice))
            .min(java.math.BigDecimal::compareTo)
            .orElse(java.math.BigDecimal.ZERO);
    int total = seats.size();
    int booked = bookings.size();
    int available = Math.max(0, total - booked);

    List<Venue> venues = venueRepository.findByEventId(event.getId());
    String venueName = venues.isEmpty() ? "Main Arena" : venues.get(0).getName();
    String venueLocation = venues.isEmpty() ? "Downtown" : venues.get(0).getLocation();

    return EventDto.builder()
        .id(event.getId())
        .perOrderLimit(event.getPerOrderLimit())
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
        .description(event.getDescription())
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
