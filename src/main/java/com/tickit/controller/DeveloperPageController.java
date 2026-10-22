package com.tickit.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DeveloperPageController {
    @GetMapping("/developer")
    public String developer() { return "redirect:/developer/"; }
    @GetMapping("/developer/")
    public String index() { return "forward:/developer/index.html"; }
}
