package com.tickit.init;

import com.tickit.model.*;
import com.tickit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (eventRepository.count() > 0) {
            log.info("Database already seeded with events. Skipping initialization.");
            return;
        }

        log.info("Seeding initial data for TickIt demo...");

        // 1. Create Users
        AppUser user1 = userRepository.save(AppUser.builder()
                .email("alex@tickit.live")
                .fullName("Alex Morgan")
                .passwordHash("$2a$10$hashedPasswordAlex123")
                .build());

        AppUser user2 = userRepository.save(AppUser.builder()
                .email("sam@tickit.live")
                .fullName("Sam Taylor")
                .passwordHash("$2a$10$hashedPasswordSam456")
                .build());

        AppUser botUser = userRepository.save(AppUser.builder()
                .email("jordan@tickit.live")
                .fullName("Jordan Lee")
                .passwordHash("$2a$10$hashedPasswordBot999")
                .build());

        // 2. Create Event 1: Coldplay
        Event coldplay = Event.builder()
                .name("Coldplay: Music of the Spheres World Tour")
                .description("Experience the visually stunning world tour live in an immersive real-time audio-visual spectacle.")
                .startTime(LocalDateTime.now().plusDays(14).withHour(20).withMinute(0))
                .endTime(LocalDateTime.now().plusDays(14).withHour(23).withMinute(30))
                .imageUrl("/images/concert.jpg")
                .build();
        coldplay = eventRepository.save(coldplay);

        Venue coldplayVenue = venueRepository.save(Venue.builder()
                .name("Wembley Stadium")
                .location("London, United Kingdom")
                .event(coldplay)
                .build());

        List<Seat> coldplaySeats = generateSeats(coldplayVenue, 5, 10, new BigDecimal("120.00"));
        seatRepository.saveAll(coldplaySeats);

        // Pre-book 2 seats for demonstration
        if (!coldplaySeats.isEmpty()) {
            bookingRepository.save(Booking.builder()
                    .event(coldplay)
                    .seat(coldplaySeats.get(0)) // A1
                    .user(user1)
                    .bookedAt(LocalDateTime.now().minusHours(2))
                    .status("CONFIRMED")
                    .build());

            bookingRepository.save(Booking.builder()
                    .event(coldplay)
                    .seat(coldplaySeats.get(1)) // A2
                    .user(user2)
                    .bookedAt(LocalDateTime.now().minusHours(1))
                    .status("CONFIRMED")
                    .build());
        }

        // 3. Create Event 2: Taylor Swift
        Event swift = Event.builder()
                .name("Taylor Swift: The Eras Tour")
                .description("A journey through all musical eras of Taylor Swift with record-breaking ticket sales and live stadium magic.")
                .startTime(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0))
                .endTime(LocalDateTime.now().plusDays(30).withHour(23).withMinute(0))
                .imageUrl("/images/eras.jpg")
                .build();
        swift = eventRepository.save(swift);

        Venue swiftVenue = venueRepository.save(Venue.builder()
                .name("SoFi Stadium")
                .location("Inglewood, California, USA")
                .event(swift)
                .build());

        List<Seat> swiftSeats = generateSeats(swiftVenue, 5, 10, new BigDecimal("185.00"));
        seatRepository.saveAll(swiftSeats);

        // Pre-book 1 seat for Taylor Swift
        if (!swiftSeats.isEmpty()) {
            bookingRepository.save(Booking.builder()
                    .event(swift)
                    .seat(swiftSeats.get(4)) // A5
                    .user(user2)
                    .bookedAt(LocalDateTime.now().minusDays(1))
                    .status("CONFIRMED")
                    .build());
        }

        // 4. Create Event 3: Formula 1 Night Race
        Event f1 = Event.builder()
                .name("Formula 1 Grand Prix 2026: Night Race")
                .description("High-speed twilight racing under 1,600 floodlights on the challenging street circuit.")
                .startTime(LocalDateTime.now().plusDays(45).withHour(20).withMinute(0))
                .endTime(LocalDateTime.now().plusDays(45).withHour(22).withMinute(30))
                .imageUrl("/images/racing.jpg")
                .build();
        f1 = eventRepository.save(f1);

        Venue f1Venue = venueRepository.save(Venue.builder()
                .name("Marina Bay Street Circuit")
                .location("Downtown Marina Bay, Singapore")
                .event(f1)
                .build());

        List<Seat> f1Seats = generateSeats(f1Venue, 4, 8, new BigDecimal("290.00"));
        seatRepository.saveAll(f1Seats);

        log.info("TickIt seed completed! 3 Events, 132 Seats, 3 Demo Users created.");
    }

    private List<Seat> generateSeats(Venue venue, int rows, int cols, BigDecimal basePrice) {
        List<Seat> list = new ArrayList<>();
        char rowLetter = 'A';
        for (int r = 0; r < rows; r++) {
            String rowName = String.valueOf((char) (rowLetter + r));
            BigDecimal priceModifier = basePrice.add(BigDecimal.valueOf((rows - r) * 15L));
            for (int c = 1; c <= cols; c++) {
                list.add(Seat.builder()
                        .venue(venue)
                        .seatRow(rowName)
                        .seatNumber(c)
                        .price(priceModifier)
                        .build());
            }
        }
        return list;
    }
}
