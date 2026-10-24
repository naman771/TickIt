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

  @PostConstruct
  void validate() {
    if (!java.util.Set.of("smtp", "brevo").contains(transport))
      throw new IllegalStateException("EMAIL_TRANSPORT must be smtp or brevo");
    if (enabled && (from.isBlank() || ("brevo".equals(transport) && brevoApiKey.isBlank())))
      throw new IllegalStateException(
          "Enabled email requires a verified sender and transport credentials");
  }
}
