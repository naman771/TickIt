package com.tickit.controller;

import com.tickit.service.DeveloperAccess;
import com.tickit.service.PipelineDiagnostics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/developer/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {
  private final DeveloperAccess access;
  private final PipelineDiagnostics diagnostics;

  public record RunRequest(@Min(8) @Max(32) int attempts) {}

  @GetMapping
  public Map<String, Object> status() {
    access.requireDeveloper();
    return diagnostics.status();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> start(@Valid @RequestBody RunRequest input) {
    access.requireDeveloper();
    return diagnostics.start(input.attempts());
  }
}
