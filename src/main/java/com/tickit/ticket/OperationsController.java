package com.tickit.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class OperationsController {
  private final OrganizerOperations operations;

  public record Staff(@NotBlank @Email @Size(max = 255) String email) {}

  @GetMapping("/api/entry/events")
  public ResponseEntity<List<Map<String, Object>>> entryEvents() {
    return privateResponse(operations.entryEvents());
  }

  @GetMapping("/api/organizer/events/{event}/operations")
  public ResponseEntity<Map<String, Object>> report(
      @PathVariable long event, @RequestParam(defaultValue = "0") int page) {
    return privateResponse(operations.report(event, page));
  }

  @PostMapping("/api/organizer/events/{event}/staff")
  public List<Map<String, Object>> grant(
      @PathVariable long event, @Valid @RequestBody Staff input) {
    operations.grant(event, input.email());
    return operations.staff(event);
  }

  @DeleteMapping("/api/organizer/events/{event}/staff/{user}")
  public List<Map<String, Object>> revoke(@PathVariable long event, @PathVariable long user) {
    operations.revoke(event, user);
    return operations.staff(event);
  }

  @GetMapping("/api/organizer/events/{event}/guests.csv")
  public ResponseEntity<byte[]> csv(
      @PathVariable long event, @RequestParam(defaultValue = "0") int page) {
    StringBuilder csv =
        new StringBuilder("Ticket reference,Admission,Guest,Email,Checked in,Order,Refund\r\n");
    for (var row : operations.guests(event, page)) {
      for (String key :
          List.of(
              "id", "label", "full_name", "email", "checked_in_at", "order_id", "refund_status")) {
        String value = Objects.toString(row.get(key), "");
        // Spreadsheet formula injection protection for organizer-supplied event labels and
        // profiles.
        if (value.matches("^[\\s]*[=+@-].*") || value.startsWith("\t") || value.startsWith("\r"))
          value = "'" + value;
        csv.append('"').append(value.replace("\"", "\"\"")).append("\",");
      }
      csv.setLength(csv.length() - 1);
      csv.append("\r\n");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            "Content-Disposition",
            "attachment; filename=\"tickit-guests-page-" + (page + 1) + ".csv\"")
        .body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  private <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
