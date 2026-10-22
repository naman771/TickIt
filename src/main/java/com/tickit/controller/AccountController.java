package com.tickit.controller;

import com.tickit.config.AuthSettings;
import com.tickit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AccountController {
    private final AuthSettings settings;
    private final CurrentUserService currentUser;

    @GetMapping("/api/public/config")
    public Map<String, Object> config() {
        return Map.of("authProvider", settings.isClerk() ? "clerk" : "unconfigured",
                "publishableKey", settings.isClerk() ? settings.getPublishableKey() : "",
                "checkoutEnabled", false, "currency", "USD");
    }

    @GetMapping("/api/me")
    public Map<String, Object> me() {
        var user = currentUser.resolve(null);
        return Map.of("id", user.getId(), "name", user.getFullName(), "email", user.getEmail());
    }
}
