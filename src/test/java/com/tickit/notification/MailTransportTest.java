package com.tickit.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

class MailTransportTest {
  JavaMailSender smtp;
  HttpClient http;
  EmailSettings settings;
  MailTransport transport;

  @BeforeEach
  void setup() {
    smtp = mock(JavaMailSender.class);
    http = mock(HttpClient.class);
    settings = new EmailSettings();
    settings.setEnabled(true);
    settings.setTransport("brevo");
    settings.setFrom("tickets@tickit.test");
    settings.setBrevoApiKey("private-test-key");
    transport = new MailTransport(smtp, settings, new ObjectMapper(), http);
  }

  @SuppressWarnings("unchecked")
  void response(int status, String body) throws Exception {
    HttpResponse<String> res = mock(HttpResponse.class);
    when(res.statusCode()).thenReturn(status);
    when(res.body()).thenReturn(body);
    when(http.send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(res);
  }

  @Test
  void sendsThroughFixedHttpsOriginWithBoundedTimeout() throws Exception {
    response(201, "{\"messageId\":\"accepted-fixture\"}");
    transport.send(UUID.randomUUID(), "guest@tickit.test", "Your tickets", "Open My tickets");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(request.capture(), any());
    assertEquals("https://api.brevo.com/v3/smtp/email", request.getValue().uri().toString());
    assertEquals("POST", request.getValue().method());
    assertTrue(request.getValue().timeout().isPresent());
    assertEquals(
        "private-test-key", request.getValue().headers().firstValue("api-key").orElseThrow());
    verifyNoInteractions(smtp);
  }

  @Test
  void rejectionOrMissingAcceptanceIsNeverCountedAsSuccessfulSend() throws Exception {
    for (var value :
        List.of(
            Map.entry(429, "private provider response"),
            Map.entry(400, "private provider response"),
            Map.entry(201, "{}"))) {
      response(value.getKey(), value.getValue());
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> transport.send(UUID.randomUUID(), "guest@tickit.test", "Tickets", "Body"));
      assertFalse(ex.getMessage().contains("private"));
    }
  }

  @Test
  void disabledTransportAndInvalidAddressCannotMakeExternalRequests() {
    settings.setEnabled(false);
    assertThrows(
        IllegalStateException.class,
        () -> transport.send(UUID.randomUUID(), "guest@tickit.test", "Tickets", "Body"));
    settings.setEnabled(true);
    assertThrows(
        Exception.class,
        () -> transport.send(UUID.randomUUID(), "not-an-address", "Tickets", "Body"));
    verifyNoInteractions(http, smtp);
  }

  @Test
  void enabledConfigurationRequiresCredentialsAndApprovedSender() {
    settings.setBrevoApiKey("");
    assertThrows(IllegalStateException.class, settings::validate);
    settings.setEnabled(false);
    assertDoesNotThrow(settings::validate);
    settings.setTransport("unsupported");
    assertThrows(IllegalStateException.class, settings::validate);
  }
}
