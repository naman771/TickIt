package com.tickit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookingFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/events should return seeded events with venue information")
    void shouldReturnEventsList() throws Exception {
        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].name", notNullValue()))
                .andExpect(jsonPath("$[0].venueName", notNullValue()))
                .andExpect(jsonPath("$[0].totalSeats", greaterThan(0)));
    }

    @Test
    @DisplayName("GET /api/events/1/seats should return seat grid with status")
    void shouldReturnSeatGrid() throws Exception {
        mockMvc.perform(get("/api/events/1/seats"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].row", notNullValue()))
                .andExpect(jsonPath("$[0].number", greaterThan(0)))
                .andExpect(jsonPath("$[0].status", anyOf(is("FREE"), is("HELD"), is("BOOKED"))));
    }

    @Test
    @DisplayName("Holding an already permanently booked seat should return 409 Conflict")
    void shouldRejectHoldOnBookedSeat() throws Exception {
        // Seat A1 (seatId: 1) on Event 1 was pre-booked during DataInitializer
        String requestJson = """
                {
                    "eventId": 1,
                    "seatId": 1,
                    "userId": 2
                }
                """;

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("already booked")));
    }

    @Test
    @DisplayName("Booking without an active hold should return 410 Gone")
    void shouldRejectBookingWithoutHold() throws Exception {
        String requestJson = """
                {
                    "eventId": 1,
                    "seatId": 99999,
                    "userId": 1
                }
                """;

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", containsString("No active hold")));
    }
}
