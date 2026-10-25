package com.tickit.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CustomerController {
  private final CustomerService service;

  public record Request(
      @NotNull UUID orderId,
      @NotBlank @Pattern(regexp = "PAYMENT|REFUND|ENTRY|EVENT|OTHER") String category,
      @NotBlank @Size(max = 3000) String message) {}

  public record Reply(@NotBlank @Size(max = 3000) String reply) {}

  @GetMapping("/api/account/saved-events")
  public ResponseEntity<List<Long>> saved() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.saved());
  }

  @PutMapping("/api/account/saved-events/{event}")
  public Map<String, Boolean> save(@PathVariable long event) {
    service.save(event);
    return Map.of("saved", true);
  }

  @DeleteMapping("/api/account/saved-events/{event}")
  public Map<String, Boolean> unsave(@PathVariable long event) {
    service.unsave(event);
    return Map.of("saved", false);
  }

  @GetMapping("/api/account/support")
  public ResponseEntity<List<Map<String, Object>>> requests() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.requests());
  }

  @PostMapping("/api/account/support")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, UUID> support(@Valid @RequestBody Request input) {
    return Map.of("id", service.support(input.orderId(), input.category(), input.message()));
  }

  @PostMapping("/api/account/orders/{order}/resend")
  public Map<String, String> resend(@PathVariable UUID order) {
    service.resend(order);
    return Map.of("status", "QUEUED");
  }

  @GetMapping("/api/developer/support")
  public ResponseEntity<List<Map<String, Object>>> review() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.review());
  }

  @PostMapping("/api/developer/support/{id}/reply")
  public Map<String, String> reply(@PathVariable UUID id, @Valid @RequestBody Reply input) {
    service.reply(id, input.reply());
    return Map.of("status", "RESOLVED");
  }
}
