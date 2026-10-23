package com.tickit.controller;

import com.tickit.dto.OrganizerEventRequest;
import com.tickit.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organizer")
@RequiredArgsConstructor
public class OrganizerController {
  private final OrganizerService service;

  public record Profile(@NotBlank @Size(min = 2, max = 120) String displayName) {}

  public record Revision(@NotNull Long version) {}

  @PostMapping("/profile")
  public Map<String, Object> enroll(@Valid @RequestBody Profile profile) {
    var a = service.enroll(profile.displayName());
    return Map.of("displayName", a.getDisplayName(), "id", a.getId());
  }

  @GetMapping("/profile")
  public Map<String, Object> profile() {
    var a = service.account();
    return Map.of("displayName", a.getDisplayName(), "id", a.getId());
  }

  @GetMapping("/events")
  public List<Map<String, Object>> events() {
    return service.list();
  }

  @GetMapping("/events/{id}")
  public Map<String, Object> event(@PathVariable Long id) {
    return service.get(id);
  }

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(@Valid @RequestBody OrganizerEventRequest input) {
    return service.save(null, input);
  }

  @PutMapping("/events/{id}")
  public Map<String, Object> update(
      @PathVariable Long id, @Valid @RequestBody OrganizerEventRequest input) {
    return service.save(id, input);
  }

  @PostMapping("/events/{id}/publish")
  public Map<String, Object> publish(@PathVariable Long id, @Valid @RequestBody Revision revision) {
    return service.publish(id, revision.version());
  }
}
