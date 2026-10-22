package com.tickit.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootPageController {
    @Value("${app.developer.host:developers.tickit.site}") private String developerHost;
    @GetMapping("/")
    public String index(HttpServletRequest request) {
        return developerHost.equalsIgnoreCase(request.getServerName())
                ? "forward:/developer/index.html" : "forward:/index.html";
    }
}
