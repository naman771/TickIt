package com.tickit.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DeveloperPageController {
    @GetMapping("/developer")
    public String developer(HttpServletRequest request) {
        return "redirect:/developer/"
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    }
    @GetMapping("/developer/")
    public String index() { return "forward:/developer/index.html"; }
}
