package com.tickit.ticket;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.tickets.recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TicketTask {
  private final TicketService tickets;

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void run() {
    tickets.recoverIssuance();
  }
}
