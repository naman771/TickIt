package com.tickit.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adapts standard provider URLs to JDBC without exposing credentials in URLs or logs. */
public class NeonDatabaseEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String supplied = env.getProperty("DATABASE_URL");
        if (supplied == null || supplied.isBlank()) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        Connection applicationConnection = parse(supplied);
        properties.put("spring.datasource.url", applicationConnection.url());
        properties.put("spring.datasource.username", applicationConnection.username());
        properties.put("spring.datasource.password", applicationConnection.password());
        String direct = env.getProperty("DATABASE_URL_UNPOOLED");
        Connection migration = direct == null || direct.isBlank() ? applicationConnection.direct() : parse(direct);
        properties.put("spring.flyway.url", migration.url());
        properties.put("spring.flyway.user", migration.username());
        properties.put("spring.flyway.password", migration.password());
        // Explicit Spring configuration continues to take precedence over provider defaults.
        env.getPropertySources().addLast(new MapPropertySource("neonDatabase", properties));
        // application.yml has local defaults; insert these provider values before that file.
        String configSource = env.getPropertySources().stream().map(s -> s.getName())
                .filter(n -> n.startsWith("Config resource")).findFirst().orElse(null);
        if (configSource != null) {
            env.getPropertySources().remove("neonDatabase");
            env.getPropertySources().addBefore(configSource, new MapPropertySource("neonDatabase", properties));
        }
    }

    static Connection parse(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!("postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() == null
                    || uri.getRawPath() == null || uri.getRawPath().length() < 2 || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            if (credentials.length != 2) throw new IllegalArgumentException();
            String query = uri.getRawQuery();
            if (query != null) query = query.replace("channel_binding=", "channelBinding=");
            String host = uri.getHost();
            String url = "jdbc:postgresql://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + uri.getRawPath() + (query == null ? "" : "?" + query);
            return new Connection(url, decode(credentials[0]), decode(credentials[1]), host);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("DATABASE_URL must be a valid PostgreSQL connection URL");
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    record Connection(String url, String username, String password, String host) {
        Connection direct() {
            if (!host.endsWith(".neon.tech") || !host.contains("-pooler.")) return this;
            return new Connection(url.replace(host, host.replace("-pooler.", ".")), username, password,
                    host.replace("-pooler.", "."));
        }
        @Override public String toString() { return "PostgreSQL connection [credentials redacted]"; }
    }
}
