package com.tickit.monitoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.exception.*;
import io.sentry.*;
import io.sentry.protocol.*;
import io.sentry.transport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class BackendErrorMonitorTest {
  static final String DSN = "https://publictestkey@o0.ingest.sentry.io/123";
  static final String PRIVATE = "customer-secret@example.test credential-super-private pay_private";

  static class RecordingTransport implements ITransport {
    final List<String> payloads = new ArrayList<>();

    @Override
    public void send(SentryEnvelope envelope, Hint hint) throws IOException {
      try {
        for (var item : envelope.getItems())
          payloads.add(new String(item.getData(), StandardCharsets.UTF_8));
      } catch (Exception ex) {
        throw new IOException("Synthetic transport failure");
      }
    }

    @Override
    public void flush(long timeout) {}

    @Override
    public RateLimiter getRateLimiter() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public void close(boolean restarting) {}
  }

  @RestController
  static class BrokenRoute {
    @PostMapping("/api/tickets/{id}")
    String broken() {
      throw new IllegalStateException(PRIVATE);
    }
  }

  @Test
  void realSdkEnvelopeContainsNoHttpUserMessagesOrEnrichedContext() throws Exception {
    var transport = new RecordingTransport();
    var options = BackendErrorMonitor.options(DSN, "test", "abc123");
    options.setTransportFactory((o, request) -> transport);
    options.addEventProcessor(
        new EventProcessor() {
          @Override
          public SentryEvent process(SentryEvent event, Hint hint) {
            var request = new Request();
            request.setUrl("https://private.example/api/tickets/credential-super-private");
            request.setHeaders(Map.of("Authorization", PRIVATE));
            request.setQueryString(PRIVATE);
            request.setData(PRIVATE);
            event.setRequest(request);
            var user = new User();
            user.setEmail(PRIVATE);
            event.setUser(user);
            event.addBreadcrumb(PRIVATE);
            event.setExtra("private", PRIVATE);
            event.getContexts().put("custom", PRIVATE);
            event.setUnknown(Map.of("secret", PRIVATE));
            event.setTransaction(PRIVATE);
            var message = new Message();
            message.setFormatted(PRIVATE);
            event.setMessage(message);
            return event;
          }
        });
    try (var client = new ClientOwner(new SentryClient(options))) {
      var monitor = new BackendErrorMonitor(client.value, Duration.ZERO, System::nanoTime);
      var advice = new GlobalExceptionHandler();
      advice.setMonitor(monitor);
      var mvc =
          MockMvcBuilders.standaloneSetup(new BrokenRoute()).setControllerAdvice(advice).build();
      mvc.perform(
              post("/api/tickets/credential-super-private")
                  .header("Authorization", PRIVATE)
                  .header("Cookie", PRIVATE)
                  .param("credential", PRIVATE)
                  .contentType("application/json")
                  .content("{\"email\":\"customer-secret@example.test\"}"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.error").value("Internal server error"));
      assertEquals(1, transport.payloads.size());
      String json = transport.payloads.getFirst();
      for (String secret :
          List.of("customer-secret", "credential-super-private", "pay_private", "private.example"))
        assertFalse(json.contains(secret), json);
      assertTrue(json.contains("IllegalStateException"));
      assertTrue(json.contains("BackendErrorMonitorTest"));
      assertTrue(json.contains("HTTP_500"));
      assertTrue(json.contains("abc123"));
      assertFalse(json.contains("\"request\""));
      assertFalse(json.contains("\"user\""));
      assertFalse(json.contains("\"breadcrumbs\""));
      assertTrue(
          options
              .getSerializer()
              .deserialize(new java.io.StringReader(json), SentryEvent.class)
              .getContexts()
              .isEmpty());
      assertNull(options.getCacheDirPath());
      assertFalse(options.getLogs().isEnabled());
      assertEquals(0d, options.getTracesSampleRate());
    }
  }

  @Test
  void concurrentErrorsHaveOneGlobalCooldownAndResumeAfterTheInterval() throws Exception {
    var client = mock(ISentryClient.class);
    when(client.captureEvent(any(), isNull(), any())).thenReturn(new SentryId());
    var clock = new AtomicLong(0);
    var monitor = new BackendErrorMonitor(client, Duration.ofMinutes(5), clock::get);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 40; i++)
        futures.add(
            pool.submit(
                () ->
                    monitor.report(
                        new RuntimeException(PRIVATE), BackendErrorMonitor.Kind.HTTP_500)));
      for (var f : futures) f.get(5, TimeUnit.SECONDS);
      verify(client, times(1)).captureEvent(any(), isNull(), any());
      assertEquals(39L, monitor.status().get("suppressed"));
      clock.set(Duration.ofMinutes(5).toNanos());
      monitor.report(new RuntimeException(PRIVATE), BackendErrorMonitor.Kind.HTTP_500);
      verify(client, times(2)).captureEvent(any(), isNull(), any());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void disabledMonitoringDoesNotRequireCredentialsOrSubmitAnything() {
    var monitor = new BackendErrorMonitor(false, "", "production", "", 300);
    monitor.report(new IllegalStateException(PRIVATE), BackendErrorMonitor.Kind.HTTP_500);
    assertEquals("DISABLED", monitor.status().get("state"));
    assertEquals(0L, monitor.status().get("submittedToSdk"));
    monitor.close();
  }

  @Test
  void monitoringProviderFailuresCannotReplaceTheApiResponse() {
    var client = mock(ISentryClient.class);
    when(client.captureEvent(any(), isNull(), any())).thenThrow(new IllegalStateException(PRIVATE));
    var monitor = new BackendErrorMonitor(client, Duration.ZERO, System::nanoTime);
    var advice = new GlobalExceptionHandler();
    advice.setMonitor(monitor);
    var response = advice.handleGeneric(new IllegalStateException(PRIVATE));
    assertEquals(500, response.getStatusCode().value());
    assertFalse(response.getBody().toString().contains(PRIVATE));
    assertEquals(1L, monitor.status().get("submissionFailures"));
  }

  @Test
  void expectedClientErrorsAreNotReportedButInfrastructureFailuresAre() {
    var monitor = mock(BackendErrorMonitor.class);
    var advice = new GlobalExceptionHandler();
    advice.setMonitor(monitor);
    advice.handleRateLimit(new RateLimitExceededException(1));
    advice.handleBadRequest(new IllegalArgumentException(PRIVATE));
    verifyNoInteractions(monitor);
    var dependency = new org.springframework.dao.DataAccessResourceFailureException(PRIVATE);
    assertEquals(503, advice.handleDependencyFailure(dependency).getStatusCode().value());
    verify(monitor).report(dependency, BackendErrorMonitor.Kind.DEPENDENCY_503);
  }

  @Test
  void invalidConfigurationIsRejectedWithoutEchoingTheDsn() {
    for (String dsn : List.of("", "http://private-key@private.example/1", "not-a-dsn")) {
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> BackendErrorMonitor.options(dsn, "test", "abc"));
      assertFalse(error.getMessage().contains("private-key"));
    }
    assertThrows(
        IllegalArgumentException.class, () -> new BackendErrorMonitor(false, "", "test", "", 1));
  }

  record ClientOwner(ISentryClient value) implements AutoCloseable {
    @Override
    public void close() {
      value.close();
    }
  }
}
