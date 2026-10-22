package com.tickit.service;

import com.tickit.config.AuthSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class DeveloperAccess {
    private final AuthSettings settings;

    public void requireDeveloper() {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (!(principal instanceof JwtAuthenticationToken session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in");
        }
        boolean allowed = Arrays.stream(settings.getDeveloperSubjects().split(","))
                .map(String::trim).anyMatch(session.getToken().getSubject()::equals);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Developer access is required");
    }
}
