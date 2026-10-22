package com.tickit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickit.config.AuthSettings;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Duration;

@Service
public class ClerkUserClient {
    private final RestClient client;
    private final AuthSettings settings;
    public record Profile(String email, String fullName) {}

    public ClerkUserClient(AuthSettings settings) {
        this.settings = settings;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl("https://api.clerk.com/v1")
                .requestFactory(factory).build();
    }

    public Profile fetch(String subject) {
        JsonNode user;
        try {
            user = client.get().uri("/users/{id}", subject)
                    .header("Authorization", "Bearer " + settings.getSecretKey())
                    .retrieve().body(JsonNode.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Account service is temporarily unavailable");
        }
        if (user == null || !subject.equals(user.path("id").asText())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid account");
        }
        String primary = user.path("primary_email_address_id").asText();
        for (JsonNode email : user.path("email_addresses")) {
            if (primary.equals(email.path("id").asText())
                    && "verified".equals(email.path("verification").path("status").asText())) {
                String address = email.path("email_address").asText();
                if (address.isBlank()) break;
                String name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).trim();
                return new Profile(address, name.isBlank() ? address.split("@")[0] : name);
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email address before reserving tickets");
    }
}
