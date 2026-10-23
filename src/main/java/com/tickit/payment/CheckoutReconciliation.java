package com.tickit.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.payments.reconciliation-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CheckoutReconciliation {
  private final PaymentSettings settings;
  private final CheckoutStore store;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final CheckoutService service;

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void reconcile() {
    if (!settings.isEnabled()) return;
    for (var order : store.reconcilable()) {
      try {
        service.reconcileOrder(order);
      } catch (Exception ex) {
        log.warn("Payment reconciliation deferred for order {}", order.id());
      } finally {
        jdbc.update(
            "UPDATE checkout_order SET last_checked_at=clock_timestamp() WHERE id=?", order.id());
      }
    }
  }
}
