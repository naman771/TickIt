package com.tickit.controller;

import com.tickit.service.AdmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class AdmissionController {
  private final AdmissionService service;

  public record HoldRequest(
      @NotNull Long ticketTypeId,
      @Min(1) @Max(10) int quantity,
      @Min(0) @Max(120) Integer attendeeAge) {}

  @GetMapping("/{id}/admission")
  public List<Map<String, Object>> availability(@PathVariable Long id) {
    return service.availability(id);
  }

  @PostMapping("/{id}/admission-holds")
  public Map<String, Object> hold(@PathVariable Long id, @Valid @RequestBody HoldRequest input) {
    return service.hold(id, input.ticketTypeId(), input.quantity(), input.attendeeAge());
  }

  @DeleteMapping("/admission-holds/{id}")
  public Map<String, Object> release(@PathVariable UUID id) {
    service.release(id);
    return Map.of("released", true);
  }
}
