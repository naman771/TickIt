package com.tickit;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in live-provider test. Authentication uses Spring's test principal; S3 and JDBC are real. */
@EnabledIfEnvironmentVariable(named = "NEON_STORAGE_E2E", matches = "true")
@ActiveProfiles("neon-e2e")
@SpringBootTest(properties = {"app.auth.mode=catalog", "app.seed.enabled=false",
        "app.auth.developer-subjects=storage_e2e_developer", "app.storage.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc
class NeonServicesE2ETest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void databaseAndPrivateStorageWorkThroughApplicationEndpoints() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/developer/assets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/developer/assets").with(jwt().jwt(j -> j.subject("customer"))))
                .andExpect(status().isForbidden());
        byte[] payload = ("TickIt live storage verification " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String result = mvc.perform(multipart("/api/developer/assets")
                        .file(new MockMultipartFile("file", "verification.txt", "text/plain", payload))
                        .with(jwt().jwt(j -> j.subject("storage_e2e_developer"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).get("id").asText();
        try {
            mvc.perform(get("/api/developer/assets/" + id).with(jwt().jwt(j -> j.subject("storage_e2e_developer"))))
                    .andExpect(status().isOk()).andExpect(content().bytes(payload))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
            String files = mvc.perform(get("/api/developer/assets")
                            .with(jwt().jwt(j -> j.subject("storage_e2e_developer"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(files).contains(id);
            URI anonymous = URI.create(System.getenv("AWS_ENDPOINT_URL_S3") + "/assets/workspace/" + id);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(anonymous)
                    .timeout(java.time.Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isIn(401, 403);
        } finally {
            mvc.perform(delete("/api/developer/assets/" + id).with(jwt().jwt(j -> j.subject("storage_e2e_developer"))))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/developer/assets/" + id).with(jwt().jwt(j -> j.subject("storage_e2e_developer"))))
                .andExpect(status().isNotFound());
        System.out.println("NEON_E2E: Database API read PASS; private upload/list/download byte match PASS; anonymous access denied PASS; delete and 404 PASS");
    }
}
