package com.tickit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {"app.auth.mode=catalog", "app.auth.developer-subjects=diagnostic-admin"})
@AutoConfigureMockMvc
class PipelineDiagnosticsTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  @Test
  void anonymousAndCustomerCannotStartOrReadTests() throws Exception {
    mvc.perform(get("/api/developer/diagnostics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/developer/diagnostics").with(jwt().jwt(j -> j.subject("customer"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/developer/diagnostics")
                .with(jwt().jwt(j -> j.subject("customer")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attempts\":16}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void boundedRealComponentRunPassesAndCleansUpWithoutChangingCatalog() throws Exception {
    var admin = jwt().jwt(j -> j.subject("diagnostic-admin"));
    mvc.perform(
            post("/api/developer/diagnostics")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attempts\":1000}"))
        .andExpect(status().isBadRequest());
    Long bookings = jdbc.queryForObject("SELECT count(*) FROM booking", Long.class);
    Long events = jdbc.queryForObject("SELECT count(*) FROM event", Long.class);
    var started =
        json.readTree(
            mvc.perform(
                    post("/api/developer/diagnostics")
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempts\":16}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(
            post("/api/developer/diagnostics")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attempts\":16}"))
        .andExpect(status().isTooManyRequests());
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          while (true) {
            var result =
                json.readTree(
                    mvc.perform(get("/api/developer/diagnostics").with(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
            if (!result.path("status").asText().equals("RUNNING")) {
              assertThat(result.path("status").asText()).isEqualTo("PASSED");
              assertThat(result.path("checks").size()).isEqualTo(4);
              assertThat(result.path("cleanupComplete").asBoolean()).isTrue();
              break;
            }
            Thread.sleep(50);
          }
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM diagnostic_claim WHERE run_id=?::uuid",
                Long.class,
                started.path("id").asText()))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM booking", Long.class)).isEqualTo(bookings);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM event", Long.class)).isEqualTo(events);
  }

  private void assertTimeoutPreemptively(
      Duration duration, org.junit.jupiter.api.function.Executable test) {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(duration, test);
  }
}
