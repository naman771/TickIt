package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tickit.config.AuthSettings;
import com.tickit.config.WebConfig;
import com.tickit.exception.GlobalExceptionHandler;
import com.tickit.interceptor.RateLimiterInterceptor;
import com.tickit.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class RateLimiterHttpTest {
  final RateLimiterService service = mock(RateLimiterService.class);
  MockMvc mvc;

  static class Registry extends InterceptorRegistry {
    HandlerInterceptor[] entries() {
      return getInterceptors().toArray(HandlerInterceptor[]::new);
    }
  }

  @RestController
  static class Routes {
    @RequestMapping({
      "/api/events",
      "/api/events/1/seats",
      "/api/public/config",
      "/api/me",
      "/api/developer/assets",
      "/api/developer/diagnostics",
      "/api/developer/overview",
      "/api/holds",
      "/api/tickets/123",
      "/api/account/profile",
      "/api/organizer/events",
      "/api/entry/events",
      "/api/payments/orders",
      "/api/payments/webhook",
      "/api/future-feature",
      "/actuator/health",
      "/index.html"
    })
    String route() {
      return "ok";
    }
  }

  @BeforeEach
  void setup() {
    var registry = new Registry();
    new WebConfig(new RateLimiterInterceptor(service, new AuthSettings()))
        .addInterceptors(registry);
    mvc =
        MockMvcBuilders.standaloneSetup(new Routes())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(registry.entries())
            .build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/events",
        "/api/events/1/seats",
        "/api/public/config",
        "/api/me",
        "/api/developer/assets",
        "/api/developer/diagnostics",
        "/api/developer/overview",
        "/api/holds",
        "/api/tickets/123",
        "/api/account/profile",
        "/api/organizer/events",
        "/api/entry/events",
        "/api/payments/orders",
        "/api/future-feature"
      })
  void allApiHandlersRejectExhaustedClientsWithActionableHeaders(String path) throws Exception {
    when(service.check("rl:ip:127.0.0.1"))
        .thenReturn(new RateLimiterService.Decision(false, 20, 0, 7, 2000000000));
    mvc.perform(get(path))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "7"))
        .andExpect(header().string("X-RateLimit-Limit", "20"))
        .andExpect(header().string("X-RateLimit-Remaining", "0"))
        .andExpect(header().string("X-RateLimit-Reset", "2000000000"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/payments/webhook", "/actuator/health", "/index.html"})
  void callbacksHealthAndStaticFilesDoNotConsumeQuota(String path) throws Exception {
    mvc.perform(post(path)).andExpect(status().isOk());
    verifyNoInteractions(service);
  }

  @Test
  void preflightDoesNotConsumeQuota() throws Exception {
    mvc.perform(options("/api/events")).andExpect(status().isOk());
    verifyNoInteractions(service);
  }

  @Test
  void unavailableRedisReturnsSanitized503InsteadOfQuota429() throws Exception {
    when(service.check(anyString()))
        .thenThrow(
            new org.springframework.data.redis.RedisConnectionFailureException(
                "private-provider-host"));
    var result =
        mvc.perform(get("/api/events"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().doesNotExist("Retry-After"))
            .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains("private-provider-host"));
  }
}
