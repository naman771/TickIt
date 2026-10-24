package com.tickit.notification;

import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** SMTP for local/compatible hosts, HTTPS for Render Free. Tests never deliver real messages. */
@Component
public class MailTransport {
  private final JavaMailSender sender;
  private final EmailSettings settings;
  private final ObjectMapper json;
  private final HttpClient http;

  @Autowired
  public MailTransport(JavaMailSender sender, EmailSettings settings, ObjectMapper json) {
    this(
        sender,
        settings,
        json,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  MailTransport(JavaMailSender sender, EmailSettings settings, ObjectMapper json, HttpClient http) {
    this.sender = sender;
    this.settings = settings;
    this.json = json;
    this.http = http;
  }

  public void send(UUID id, String to, String subject, String body) throws Exception {
    if (!settings.isEnabled()) throw new IllegalStateException("Email delivery disabled");
    new InternetAddress(settings.getFrom(), true).validate();
    new InternetAddress(to, true).validate();
    if ("brevo".equals(settings.getTransport())) {
      var payload =
          Map.of(
              "sender",
              Map.of("name", "TickIt", "email", settings.getFrom()),
              "to",
              List.of(Map.of("email", to)),
              "subject",
              subject,
              "textContent",
              body,
              "headers",
              Map.of("X-Tickit-Notification", id.toString()));
      var request =
          HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
              .timeout(Duration.ofSeconds(15))
              .header("api-key", settings.getBrevoApiKey())
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      try {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201
            || json.readTree(response.body()).path("messageId").asText().isBlank())
          throw new IllegalStateException("Mail provider did not accept the notification");
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Mail request interrupted");
      }
      return;
    }
    var message = sender.createMimeMessage();
    var helper = new MimeMessageHelper(message, false, "UTF-8");
    helper.setFrom(new InternetAddress(settings.getFrom(), true));
    helper.setTo(new InternetAddress(to, true));
    helper.setSubject(subject);
    helper.setText(body, false);
    message.saveChanges();
    message.setHeader("Message-ID", "<" + id + "@tickit.notification>");
    // Delivery is at-least-once: accepted messages followed by lost acknowledgements can duplicate.
    sender.send(message);
  }
}
