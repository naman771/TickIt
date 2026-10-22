package com.tickit.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class NeonDatabaseEnvironmentTest {
    @Test void mapsCredentialsAndUsesDirectMigrationConnection() {
        MockEnvironment env = new MockEnvironment().withProperty("DATABASE_URL",
                "postgresql://owner:p%40ss+word@ep-example-pooler.eu-central-1.aws.neon.tech/tickitdb?sslmode=require&channel_binding=require");
        env.getPropertySources().addLast(new MapPropertySource("Config resource application.yml", Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost/tickit")));
        new NeonDatabaseEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.url")).contains("-pooler.", "sslmode=require", "channelBinding=require").doesNotContain("owner", "p%40");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss+word");
        assertThat(env.getProperty("spring.flyway.url")).doesNotContain("-pooler.");
    }

    @Test void preservesExplicitSpringSettingsAndSeparateMigrationCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgresql://app:secret@ep-example-pooler.neon.tech/db")
                .withProperty("DATABASE_URL_UNPOOLED", "postgresql://migration:other@ep-example.neon.tech/db")
                .withProperty("spring.datasource.url", "jdbc:postgresql://custom/db");
        new NeonDatabaseEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://custom/db");
        assertThat(env.getProperty("spring.flyway.user")).isEqualTo("migration");
    }

    @Test void rejectsMalformedUrlsWithoutLeakingInput() {
        assertThatThrownBy(() -> NeonDatabaseEnvironment.parse("postgresql://secret-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DATABASE_URL must be a valid PostgreSQL connection URL");
    }
}
