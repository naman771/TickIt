package com.tickit.controller;

import com.tickit.dto.EventDto;
import com.tickit.dto.SeatDto;
import com.tickit.model.*;
import com.tickit.repository.BookingRepository;
import com.tickit.repository.EventRepository;
import com.tickit.repository.SeatRepository;
import com.tickit.repository.VenueRepository;
import com.tickit.service.SeatHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

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

    @GetMapping
    public ResponseEntity<List<EventDto>> getAllEvents() {
        List<Event> events = eventRepository.findAll();
        List<EventDto> dtos = events.stream().map(this::toEventDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventDto> getEventById(@PathVariable Long id) {
        return eventRepository.findById(id)
                .map(this::toEventDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/seats")
    public ResponseEntity<List<SeatDto>> getEventSeats(@PathVariable Long id) {
        if (!eventRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<Seat> seats = seatRepository.findByVenueEventId(id);
        List<Booking> bookings = bookingRepository.findByEventId(id);
        Set<Long> bookedSeatIds = bookings.stream()
                .map(b -> b.getSeat().getId())
                .collect(Collectors.toSet());

        List<SeatDto> seatDtos = seats.stream().map(seat -> {
            String status = "FREE";
            if (bookedSeatIds.contains(seat.getId())) {
                status = "BOOKED";
            } else if (seatHoldService.isSeatHeld(id, seat.getId())) {
                status = "HELD";
            }

            return SeatDto.builder()
                    .seatId(seat.getId())
                    .row(seat.getSeatRow())
                    .number(seat.getSeatNumber())
                    .label(seat.getSeatRow() + seat.getSeatNumber())
                    .price(seat.getPrice())
                    .status(status)
                    .build();
        }).sorted(Comparator.comparing(SeatDto::getRow).thenComparingInt(SeatDto::getNumber))
          .collect(Collectors.toList());

        return ResponseEntity.ok(seatDtos);
    }

    private EventDto toEventDto(Event event) {
        List<Seat> seats = seatRepository.findByVenueEventId(event.getId());
        List<Booking> bookings = bookingRepository.findByEventId(event.getId());

        int total = seats.size();
        int booked = bookings.size();
        int available = Math.max(0, total - booked);

        List<Venue> venues = venueRepository.findByEventId(event.getId());
        String venueName = venues.isEmpty() ? "Main Arena" : venues.get(0).getName();
        String venueLocation = venues.isEmpty() ? "Downtown" : venues.get(0).getLocation();

        return EventDto.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .imageUrl(event.getImageUrl())
                .venueName(venueName)
                .venueLocation(venueLocation)
                .totalSeats(total)
                .availableSeats(available)
                .build();
    }
}
