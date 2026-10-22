package com.tickit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthSettings auth, Environment environment) throws Exception {
        if (!java.util.Set.of("catalog", "clerk", "test").contains(auth.getMode())) {
            throw new IllegalStateException("APP_AUTH_MODE must be catalog or clerk");
        }
        if (auth.isTest() && !environment.acceptsProfiles(Profiles.of("test"))) {
            throw new IllegalStateException("Test authentication is restricted to the test profile");
        }
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json");
                    res.getWriter().write("{\"error\": \"Please sign in to continue\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json");
                    res.getWriter().write("{\"error\": \"Access denied\"}");
                }));
        if (auth.isTest()) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**", "/api/public/config").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());
        }
        if (auth.isClerk()) {
            if (!auth.getIssuer().startsWith("https://") || auth.getPublishableKey().isBlank()
                    || auth.getSecretKey().isBlank() || auth.origins().isEmpty()) {
                throw new IllegalStateException("Clerk requires issuer, publishable key, secret key and allowed origins");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(auth.getIssuer().replaceAll("/$", "") + "/.well-known/jwks.json").build();
            decoder.setJwtValidator(ClerkTokenValidators.create(auth.getIssuer(), auth.origins()));
            http.oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder)));
        }
        return http.build();
    }
}
