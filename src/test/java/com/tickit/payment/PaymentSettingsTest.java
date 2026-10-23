package com.tickit.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaymentSettingsTest {
  @Test
  void disabledIntegrationNeedsNoAccount() {
    new PaymentSettings().validate();
  }

  @Test
  void liveKeysAndMissingWebhookConfigurationCannotEnablePayments() {
    var s = new PaymentSettings();
    s.setEnabled(true);
    assertThrows(IllegalStateException.class, s::validate);
    s.setKeyId("rzp_live_notallowed");
    s.setKeySecret("fixture");
    s.setWebhookSecret("fixture-webhook-secret-long-enough");
    assertThrows(IllegalStateException.class, s::validate);
    s.setKeyId("rzp_test_fixture");
    assertDoesNotThrow(s::validate);
  }

  @Test
  void missingMalformedAndWrongSignaturesFailClosed() {
    assertFalse(PaymentSignatures.valid(new byte[0], null, "fixture"));
    assertFalse(PaymentSignatures.valid(new byte[0], "invalid", "fixture"));
    assertFalse(PaymentSignatures.valid(new byte[0], "0".repeat(64), "fixture"));
    assertFalse(PaymentSignatures.valid(new byte[0], "0".repeat(64), ""));
  }
}
