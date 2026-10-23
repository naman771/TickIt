package com.tickit.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.payments.refunds-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RefundTask {
  private final RefundService refunds;
  private final PaymentSettings settings;

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void run() {
    if (!settings.isEnabled()) return;
    refunds.enqueueEligible(null);
    for (int i = 0; i < 10 && refunds.processOne(); i++) {
      /* Bounded durable queue drain. */
    }
  }
}
