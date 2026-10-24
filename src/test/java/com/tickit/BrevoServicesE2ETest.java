package com.tickit;

import com.tickit.notification.MailTransport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Explicitly opt-in: one real verification email; never executed by ordinary CI. */
@EnabledIfEnvironmentVariable(named = "BREVO_E2E", matches = "true")
@SpringBootTest(
    properties = {
      "app.auth.mode=catalog",
      "app.payments.enabled=false",
      "app.email.enabled=true",
      "app.email.transport=brevo",
      "app.seed.enabled=false",
      "app.notifications.scheduled-enabled=false",
      "app.tickets.recovery-enabled=false"
    })
class BrevoServicesE2ETest {
  @Autowired MailTransport transport;

  @Test
  void approvedSenderCanSubmitOneVerificationEmail() throws Exception {
    String recipient = System.getenv("EMAIL_TEST_RECIPIENT");
    org.junit.jupiter.api.Assertions.assertNotNull(
        recipient, "An explicit verification recipient is required");
    org.junit.jupiter.api.Assertions.assertEquals(
        recipient,
        System.getenv("EMAIL_ALLOWED_RECIPIENTS"),
        "Real acceptance must restrict delivery to the explicit test recipient");
    transport.send(
        UUID.randomUUID(),
        recipient,
        "TickIt email delivery check",
        "Your TickIt sender connection has been accepted by the email service.\n"
            + "This verification message does not change any booking, ticket or payment.\n"
            + "My tickets: https://tickit-mpy2.onrender.com/#tickets\n"
            + "Please confirm receipt in your inbox so delivery can be verified before enabling the"
            + " hosted queue.");
    System.out.println(
        "BREVO_ACCEPTANCE_CONFIRMED: one verification email accepted; inbox confirmation remains"
            + " separate");
  }
}
