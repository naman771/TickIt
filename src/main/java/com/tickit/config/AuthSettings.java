package com.tickit.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

@Component
@Getter
public class AuthSettings {
    @Value("${app.auth.mode:catalog}") private String mode;
    @Value("${app.auth.issuer:}") private String issuer;
    @Value("${app.auth.publishable-key:}") private String publishableKey;
    @Value("${app.auth.secret-key:}") private String secretKey;
    @Value("${app.auth.allowed-origins:}") private String allowedOrigins;

    @Value("${app.auth.developer-subjects:}") private String developerSubjects;

    public boolean isTest() { return "test".equals(mode); }
    public boolean isClerk() { return "clerk".equals(mode); }
    public List<String> origins() {
        return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
