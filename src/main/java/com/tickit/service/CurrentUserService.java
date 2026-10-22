package com.tickit.service;

import com.tickit.config.AuthSettings;
import com.tickit.model.AppUser;
import com.tickit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final AuthSettings settings;
    private final UserRepository users;
    private final ClerkUserClient clerk;

    public AppUser resolve(Long suppliedId) {
        if (settings.isTest()) {
            if (suppliedId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID is required in tests");
            return users.findById(suppliedId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user"));
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue");
        }
        String issuer = session.getToken().getIssuer().toString();
        String subject = session.getToken().getSubject();
        AppUser user = users.findByAuthIssuerAndAuthSubject(issuer, subject).orElseGet(() -> provision(issuer, subject));
        if (suppliedId != null && !suppliedId.equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot access another account");
        }
        return user;
    }

    private AppUser provision(String issuer, String subject) {
        var profile = clerk.fetch(subject);
        try {
            return users.saveAndFlush(AppUser.builder().email(profile.email()).fullName(profile.fullName())
                    .authIssuer(issuer).authSubject(subject).build());
        } catch (DataIntegrityViolationException conflict) {
            // Concurrent first requests can create the same provider identity. Never link accounts by email alone.
            return users.findByAuthIssuerAndAuthSubject(issuer, subject).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "This email is associated with a different account"));
        }
    }
}
