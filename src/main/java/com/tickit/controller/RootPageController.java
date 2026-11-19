package com.tickit.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootPageController {
  @Value("${app.developer.host:developers.tickit.site}")
  private String developerHost;

  @Value("${app.organizer.host:organizers.tickit.site}")
  private String organizerHost;

  @GetMapping("/organizer")
  public String organizerRedirect(HttpServletRequest request) {
    return "redirect:/organizer/"
        + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
  }

  @GetMapping("/organizer/")
  public String organizerIndex() {
    return "forward:/organizer/index.html";
  }

  @GetMapping("/")
  public String index(HttpServletRequest request) {
    if (organizerHost.equalsIgnoreCase(request.getServerName()))
      return organizerRedirect(request);
    return developerHost.equalsIgnoreCase(request.getServerName())
        ? "forward:/developer/index.html"
        : "forward:/index.html";
  }
}
