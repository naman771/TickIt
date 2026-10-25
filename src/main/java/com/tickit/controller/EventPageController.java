package com.tickit.controller;

import com.tickit.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class EventPageController {
  private final EventRepository events;

  @GetMapping({"/events/{slug:[a-z0-9-]+}", "/events/{slug:[a-z0-9-]+}/book"})
  public String event(@PathVariable String slug) {
    if (!events.existsBySlugAndPublicationStatus(slug, "PUBLISHED"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return "forward:/index.html";
  }
}
