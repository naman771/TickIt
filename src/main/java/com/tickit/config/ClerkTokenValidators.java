package com.tickit.config;

import java.util.List;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

public final class ClerkTokenValidators {
    private ClerkTokenValidators() {}
    public static OAuth2TokenValidator<Jwt> create(String issuer, List<String> allowedOrigins) {
        OAuth2TokenValidator<Jwt> session = token -> {
            String party = token.getClaimAsString("azp");
            boolean valid = allowedOrigins.contains(party) && token.getExpiresAt() != null
                    && token.getSubject() != null && !token.getSubject().isBlank()
                    && !"pending".equals(token.getClaimAsString("sts"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid session origin or status", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), session);
    }
}
