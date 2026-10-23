package com.tickit.payment;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.payments")
@Getter
@Setter
public class PaymentSettings {
  private boolean enabled;
  private String keyId = "";
  private String keySecret = "";
  private String webhookSecret = "";

  @PostConstruct
  void validate() {
    if (enabled
        && (!keyId.startsWith("rzp_test_") || keySecret.isBlank() || webhookSecret.length() < 24)) {
      throw new IllegalStateException(
          "Payments require Razorpay test keys and a webhook secret of at least 24 characters; live"
              + " keys are disabled");
    }
  }
}
