package com.tickit;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.model.AppUser;
import com.tickit.repository.UserRepository;
import com.tickit.service.ClerkUserClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.auth.mode=catalog",
      "app.auth.developer-subjects=auth_test_developer",
      "spring.datasource.hikari.maximum-pool-size=3",
      "spring.datasource.hikari.minimum-idle=0"
    })
@AutoConfigureMockMvc
class AccountSecurityTest {
  static final String ISSUER = "https://test.tickit.invalid";
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @MockBean ClerkUserClient clerk;
  AppUser owner;

  @BeforeEach
  void setup() {
    owner =
        users
            .findByAuthIssuerAndAuthSubject(ISSUER, "auth_test_owner")
            .orElseGet(
                () ->
                    users.saveAndFlush(
                        AppUser.builder()
                            .email("auth_security_owner@tickit.test")
                            .fullName("Security test account")
                            .authIssuer(ISSUER)
                            .authSubject("auth_test_owner")
                            .build()));
  }

  @Test
  void publicCatalogIsAccessible() throws Exception {
    mvc.perform(get("/api/events")).andExpect(status().isOk());
  }

  @Test
  void unauthenticatedHoldCannotUseForgedIdentity() throws Exception {
    mvc.perform(
            post("/api/holds")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatId\": 3, \"userId\": 1}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", notNullValue()));
  }

  @Test
  void unauthenticatedHistoryIsDenied() throws Exception {
    mvc.perform(get("/api/bookings/user/1")).andExpect(status().isUnauthorized());
  }

  @Test
  void identityComesFromVerifiedSubject() throws Exception {
    mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(owner.getId().intValue())));
  }

  @Test
  void ownHistoryDoesNotNeedUserId() throws Exception {
    mvc.perform(
            get("/api/bookings/me")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
        .andExpect(status().isOk());
  }

  @Test
  void anotherAccountsHistoryIsDenied() throws Exception {
    mvc.perform(
            get("/api/bookings/user/1")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void suppliedUserCannotImpersonateAnotherAccount() throws Exception {
    mvc.perform(
            post("/api/holds")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatId\": 3, \"userId\": 1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void checkoutCannotConfirmUnpaidTickets() throws Exception {
    mvc.perform(
            post("/api/bookings")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatId\": 3}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void developerApiRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/developer/overview")).andExpect(status().isUnauthorized());
  }

  @Test
  void customersCannotUseDeveloperApi() throws Exception {
    mvc.perform(
            get("/api/developer/overview")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void allowedDeveloperCanReadDiagnostics() throws Exception {
    mvc.perform(
            get("/api/developer/overview")
                .with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_developer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services.postgresql", is(true)))
        .andExpect(jsonPath("$.catalog.events", greaterThan(0)));
  }

  @Test
  void assetApiRejectsAnonymousAndCustomerAccess() throws Exception {
    mvc.perform(get("/api/developer/assets")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/developer/assets").with(jwt().jwt(j -> j.subject("auth_test_owner"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/developer/assets/11111111-1111-1111-1111-111111111111")
                .with(jwt().jwt(j -> j.subject("auth_test_owner"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void assetApiRejectsInvalidSizeAndIdentifiers() throws Exception {
    mvc.perform(
            multipart("/api/developer/assets")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "oversized.bin",
                        "application/octet-stream",
                        new byte[5 * 1024 * 1024 + 1]))
                .with(jwt().jwt(j -> j.subject("auth_test_developer"))))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            multipart("/api/developer/assets")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "empty.bin", "application/octet-stream", new byte[0]))
                .with(jwt().jwt(j -> j.subject("auth_test_developer"))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/developer/assets/not-an-object-id")
                .with(jwt().jwt(j -> j.subject("auth_test_developer"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void assetApiFailsClosedWithoutStorageConfiguration() throws Exception {
    mvc.perform(get("/api/developer/assets").with(jwt().jwt(j -> j.subject("auth_test_developer"))))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void publicConfigDoesNotExposeSecrets() throws Exception {
    mvc.perform(get("/api/public/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkoutEnabled", is(false)))
        .andExpect(jsonPath("$.secretKey").doesNotExist())
        .andExpect(jsonPath("$.developerSubjects").doesNotExist());
  }

  @Test
  void customerHostServesCustomerApplication() throws Exception {
    mvc.perform(
            get("/")
                .with(
                    request -> {
                      request.setServerName("tickit.site");
                      return request;
                    }))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void developerHostServesSeparateApplication() throws Exception {
    mvc.perform(
            get("/")
                .with(
                    request -> {
                      request.setServerName("developers.tickit.site");
                      return request;
                    }))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/developer/index.html"));
  }

  @Test
  void newProviderIdentityIsProvisionedFromServerProfile() throws Exception {
    String subject = "auth_new_" + java.util.UUID.randomUUID();
    org.mockito.Mockito.when(clerk.fetch(subject))
        .thenReturn(new ClerkUserClient.Profile(subject + "@tickit.test", "New account"));
    mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject(subject))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", is("New account")));
    org.junit.jupiter.api.Assertions.assertTrue(
        users.findByAuthIssuerAndAuthSubject(ISSUER, subject).isPresent());
  }

  @Test
  void matchingEmailDoesNotLinkAnUnrelatedIdentity() throws Exception {
    String subject = "auth_other_" + java.util.UUID.randomUUID();
    org.mockito.Mockito.when(clerk.fetch(subject))
        .thenReturn(new ClerkUserClient.Profile(owner.getEmail(), "Other identity"));
    mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject(subject))))
        .andExpect(status().isConflict());
    org.junit.jupiter.api.Assertions.assertTrue(
        users.findByAuthIssuerAndAuthSubject(ISSUER, subject).isEmpty());
  }

  @Autowired com.tickit.service.CurrentUserService current;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  @Test
  void concurrentFirstLoginDoesNotPoisonOuterBusinessTransactions() throws Exception {
    String subject = "race_" + java.util.UUID.randomUUID();
    var barrier = new java.util.concurrent.CyclicBarrier(3);
    org.mockito.Mockito.when(clerk.fetch(subject))
        .thenAnswer(
            invocation -> {
              barrier.await(15, java.util.concurrent.TimeUnit.SECONDS);
              return new ClerkUserClient.Profile(subject + "@tickit.test", "Concurrent account");
            });
    var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
    java.util.concurrent.Callable<Long> attempt =
        () -> {
          var token =
              org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                  .header("alg", "RS256")
                  .issuer(ISSUER)
                  .subject(subject)
                  .build();
          var context =
              org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              new org.springframework.security.oauth2.server.resource.authentication
                  .JwtAuthenticationToken(token));
          org.springframework.security.core.context.SecurityContextHolder.setContext(context);
          try {
            return new org.springframework.transaction.support.TransactionTemplate(
                    transactionManager)
                .execute(
                    tx -> {
                      Long id = current.resolve(null).getId();
                      org.junit.jupiter.api.Assertions.assertFalse(tx.isRollbackOnly());
                      return id;
                    });
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };
    try {
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      var third = pool.submit(attempt);
      Long id = first.get(45, java.util.concurrent.TimeUnit.SECONDS);
      org.junit.jupiter.api.Assertions.assertEquals(
          id, second.get(45, java.util.concurrent.TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertEquals(
          id, third.get(45, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void malformedRequestsPreserveHttpErrorSemantics() throws Exception {
    var identity = jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"));
    mvc.perform(put("/api/me").with(identity)).andExpect(status().isMethodNotAllowed());
    mvc.perform(
            post("/api/holds").with(identity).contentType(MediaType.TEXT_PLAIN).content("not json"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(get("/missing-file.js")).andExpect(status().isNotFound());
    mvc.perform(get("/api/events").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable());
    mvc.perform(
            post("/api/holds")
                .with(identity)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":1,\"seatId\":1.5}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/holds").with(identity).contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oversizedJsonIsRejectedBeforeDeserialization() throws Exception {
    byte[] body =
        ("{\"padding\":\"" + "x".repeat(com.tickit.filter.JsonBodyLimitFilter.MAX_BYTES) + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var identity = jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"));
    mvc.perform(
            post("/api/holds").with(identity).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            post("/api/holds")
                .with(identity)
                .contentType("application/vnd.tickit+json")
                .content(body)
                .with(
                    request -> {
                      request.removeHeader("Content-Length");
                      return request;
                    }))
        .andExpect(status().isPayloadTooLarge());
  }
}
