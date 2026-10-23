package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tickit.config.AuthSettings;
import com.tickit.exception.RateLimitExceededException;
import com.tickit.interceptor.RateLimiterInterceptor;
import com.tickit.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RateLimiterBoundaryTest {
  private final RateLimiterService service = mock(RateLimiterService.class);
  private final AuthSettings settings = new AuthSettings();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private RateLimiterInterceptor interceptor() {
    org.springframework.test.util.ReflectionTestUtils.setField(settings, "mode", "clerk");
    return new RateLimiterInterceptor(service, settings);
  }

  @Test
  void authenticatedRequestsUseBothBucketsAndDoNotTrustArbitraryForwardedHeaders() {
    request.setRemoteAddr("203.0.113.10");
    request.addHeader("X-Forwarded-For", "198.51.100.99");
    request.setUserPrincipal(() -> "verified-subject");
    when(service.isAllowedForIp("203.0.113.10")).thenReturn(true);
    when(service.isAllowed("rl:subject:verified-subject")).thenReturn(true);
    assertTrue(interceptor().preHandle(request, response, new Object()));
    verify(service).isAllowedForIp("203.0.113.10");
    verify(service).isAllowed("rl:subject:verified-subject");
    verifyNoMoreInteractions(service);
  }

  @Test
  void exhaustedIpBucketStopsBeforeTheUserBucket() {
    request.setRemoteAddr("203.0.113.10");
    request.setUserPrincipal(() -> "verified-subject");
    assertThrows(
        RateLimitExceededException.class,
        () -> interceptor().preHandle(request, response, new Object()));
    verify(service).isAllowedForIp("203.0.113.10");
    verifyNoMoreInteractions(service);
  }

  @Test
  void exhaustedUserBucketCannotBypassTheLimitByChangingIp() {
    request.setRemoteAddr("203.0.113.11");
    request.setUserPrincipal(() -> "verified-subject");
    when(service.isAllowedForIp("203.0.113.11")).thenReturn(true);
    assertThrows(
        RateLimitExceededException.class,
        () -> interceptor().preHandle(request, response, new Object()));
  }

  @Test
  void redisOutageFailsClosedAndHasAServiceUnavailableResponse() {
    request.setRemoteAddr("203.0.113.10");
    var outage =
        new org.springframework.data.redis.RedisConnectionFailureException(
            "internal-provider-details");
    when(service.isAllowedForIp("203.0.113.10")).thenThrow(outage);
    assertThrows(
        org.springframework.data.redis.RedisConnectionFailureException.class,
        () -> interceptor().preHandle(request, response, new Object()));
    var result = new com.tickit.exception.GlobalExceptionHandler().handleDependencyFailure(outage);
    assertEquals(503, result.getStatusCode().value());
    assertFalse(result.getBody().toString().contains("internal-provider-details"));
  }
}
