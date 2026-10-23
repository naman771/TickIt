package com.tickit.service;

import com.tickit.config.AuthSettings;
import com.tickit.model.AppUser;
import com.tickit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  public AppUser resolve(Long suppliedId) {
    if (settings.isTest()) {
      if (suppliedId == null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID is required in tests");
      return users
          .findById(suppliedId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user"));
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken session)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue");
    }
    String issuer = session.getToken().getIssuer().toString();
    String subject = session.getToken().getSubject();
    AppUser user =
        users
            .findByAuthIssuerAndAuthSubject(issuer, subject)
            .orElseGet(() -> provision(issuer, subject));
    if (suppliedId != null && !suppliedId.equals(user.getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot access another account");
    }
    return user;
  }

  private AppUser provision(String issuer, String subject) {
    var profile = clerk.fetch(subject);
    // PostgreSQL arbitrates first-request races without aborting an enclosing business transaction.
    // Join its connection when present; suspending it can exhaust a small hosted pool.
    jdbc.update(
        "INSERT INTO app_user(email, full_name, auth_issuer, auth_subject) VALUES (?,?,?,?) ON"
            + " CONFLICT DO NOTHING",
        profile.email(),
        profile.fullName(),
        issuer,
        subject);
    return users
        .findByAuthIssuerAndAuthSubject(issuer, subject)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "This email is associated with a different account"));
  }
}
