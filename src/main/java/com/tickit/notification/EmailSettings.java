package com.tickit.notification;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.email")
@Getter
@Setter
public class EmailSettings {
  private boolean enabled;
  private String from = "";
  private String transport = "smtp";
  private String brevoApiKey = "";
  // Empty means unrestricted delivery; use an explicit recipient for the first hosted acceptance.
  private String allowedRecipients = "";

  public java.util.List<String> recipients() {
    return java.util.Arrays.stream(allowedRecipients.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
        .distinct()
        .toList();
  }

  public boolean permits(String recipient) {
    return recipients().isEmpty()
        || recipients().contains(recipient.toLowerCase(java.util.Locale.ROOT));
  }

  @PostConstruct
  void validate() {
    if (!java.util.Set.of("smtp", "brevo").contains(transport))
      throw new IllegalStateException("EMAIL_TRANSPORT must be smtp or brevo");
    if (recipients().size() > 20)
      throw new IllegalStateException("Use at most 20 allowed email recipients");
    for (String address : recipients())
      try {
        new jakarta.mail.internet.InternetAddress(address, true).validate();
      } catch (Exception ex) {
        throw new IllegalStateException("Invalid email recipient allowlist");
      }
    if (enabled && (from.isBlank() || ("brevo".equals(transport) && brevoApiKey.isBlank())))
      throw new IllegalStateException(
          "Enabled email requires a verified sender and transport credentials");
  }
}
