package com.tickit;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(properties = {"app.auth.mode=catalog", "app.auth.developer-subjects=auth_test_developer"})
@AutoConfigureMockMvc
class AccountSecurityTest {
    static final String ISSUER = "https://test.tickit.invalid";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockBean ClerkUserClient clerk;
    AppUser owner;

    @BeforeEach void setup() {
        owner = users.findByAuthIssuerAndAuthSubject(ISSUER, "auth_test_owner").orElseGet(() -> users.saveAndFlush(
                AppUser.builder().email("auth_security_owner@tickit.test").fullName("Security test account")
                        .authIssuer(ISSUER).authSubject("auth_test_owner").build()));
    }
    @Test void publicCatalogIsAccessible() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isOk());
    }
    @Test void unauthenticatedHoldCannotUseForgedIdentity() throws Exception {
        mvc.perform(post("/api/holds").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatId\": 3, \"userId\": 1}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error", notNullValue()));
    }
    @Test void unauthenticatedHistoryIsDenied() throws Exception {
        mvc.perform(get("/api/bookings/user/1")).andExpect(status().isUnauthorized());
    }
    @Test void identityComesFromVerifiedSubject() throws Exception {
        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id", is(owner.getId().intValue())));
    }
    @Test void ownHistoryDoesNotNeedUserId() throws Exception {
        mvc.perform(get("/api/bookings/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
                .andExpect(status().isOk());
    }
    @Test void anotherAccountsHistoryIsDenied() throws Exception {
        mvc.perform(get("/api/bookings/user/1").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
                .andExpect(status().isForbidden());
    }
    @Test void suppliedUserCannotImpersonateAnotherAccount() throws Exception {
        mvc.perform(post("/api/holds").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\": 1, \"seatId\": 3, \"userId\": 1}"))
                .andExpect(status().isForbidden());
    }
    @Test void checkoutCannotConfirmUnpaidTickets() throws Exception {
        mvc.perform(post("/api/bookings").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\": 1, \"seatId\": 3}"))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void developerApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/developer/overview")).andExpect(status().isUnauthorized());
    }
    @Test void customersCannotUseDeveloperApi() throws Exception {
        mvc.perform(get("/api/developer/overview").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_owner"))))
                .andExpect(status().isForbidden());
    }
    @Test void allowedDeveloperCanReadDiagnostics() throws Exception {
        mvc.perform(get("/api/developer/overview").with(jwt().jwt(j -> j.issuer(ISSUER).subject("auth_test_developer"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.services.postgresql", is(true)))
                .andExpect(jsonPath("$.catalog.events", greaterThan(0)));
    }
    @Test void publicConfigDoesNotExposeSecrets() throws Exception {
        mvc.perform(get("/api/public/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled", is(false)))
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.developerSubjects").doesNotExist());
    }

    @Test void customerHostServesCustomerApplication() throws Exception {
        mvc.perform(get("/").with(request -> {request.setServerName("tickit.site");return request;}))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
    @Test void developerHostServesSeparateApplication() throws Exception {
        mvc.perform(get("/").with(request -> {request.setServerName("developers.tickit.site");return request;}))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/developer/index.html"));
    }
    @Test void newProviderIdentityIsProvisionedFromServerProfile() throws Exception {
        String subject = "auth_new_" + java.util.UUID.randomUUID();
        org.mockito.Mockito.when(clerk.fetch(subject)).thenReturn(new ClerkUserClient.Profile(subject+"@tickit.test", "New account"));
        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("New account")));
        org.junit.jupiter.api.Assertions.assertTrue(users.findByAuthIssuerAndAuthSubject(ISSUER, subject).isPresent());
    }
    @Test void matchingEmailDoesNotLinkAnUnrelatedIdentity() throws Exception {
        String subject = "auth_other_" + java.util.UUID.randomUUID();
        org.mockito.Mockito.when(clerk.fetch(subject)).thenReturn(new ClerkUserClient.Profile(owner.getEmail(), "Other identity"));
        mvc.perform(get("/api/me").with(jwt().jwt(j -> j.issuer(ISSUER).subject(subject))))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertTrue(users.findByAuthIssuerAndAuthSubject(ISSUER, subject).isEmpty());
    }
}
