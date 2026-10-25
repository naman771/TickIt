package com.tickit.customer;

import com.tickit.ticket.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class TicketLifecycleController {
  private final TicketLifecycle lifecycle;
  private final TicketService tickets;

  public record Recipient(@NotBlank @Email @Size(max = 255) String email) {}

  public record Resolution(
      @NotBlank @Pattern(regexp = "ACCEPT|DECLINE|CANCEL") String action,
      @Min(0) @Max(120) Integer age) {}

  @PostMapping("/api/tickets/{id}/transfer")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, UUID> offer(@PathVariable UUID id, @Valid @RequestBody Recipient recipient) {
    return Map.of("id", lifecycle.offer(id, recipient.email()));
  }

  @GetMapping("/api/account/transfers")
  public ResponseEntity<List<Map<String, Object>>> transfers() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lifecycle.transfers());
  }

  @PostMapping("/api/account/transfers/{id}")
  public Map<String, String> resolve(@PathVariable UUID id, @Valid @RequestBody Resolution input) {
    lifecycle.resolve(id, input.action(), input.age());
    return Map.of("status", "UPDATED");
  }

  @GetMapping("/api/account/received-tickets")
  public ResponseEntity<List<Map<String, Object>>> received() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tickets.received());
  }

  @PostMapping("/api/account/orders/{id}/cancel")
  public Map<String, String> cancel(@PathVariable UUID id) {
    lifecycle.cancelOrder(id);
    return Map.of("status", "REFUND_QUEUED");
  }
}
