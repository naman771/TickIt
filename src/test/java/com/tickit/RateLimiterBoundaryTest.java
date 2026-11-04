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

  private RateLimiterService.Decision allowed() {
    return new RateLimiterService.Decision(true, 20, 19, 0, 2000000000);
  }

  private RateLimiterInterceptor interceptor() {
    org.springframework.test.util.ReflectionTestUtils.setField(settings, "mode", "clerk");
    return new RateLimiterInterceptor(service, settings);
  }

  @Test
  void authenticatedRequestsUseBothBucketsAndDoNotTrustArbitraryForwardedHeaders() {
    request.setRemoteAddr("203.0.113.10");
    request.addHeader("X-Forwarded-For", "198.51.100.99");
    request.setUserPrincipal(() -> "verified-subject");
    when(service.check("rl:ip:203.0.113.10")).thenReturn(allowed());
    when(service.check("rl:subject:verified-subject")).thenReturn(allowed());
    assertTrue(interceptor().preHandle(request, response, new Object()));
    assertEquals("20", response.getHeader("X-RateLimit-Limit"));
    assertEquals("19", response.getHeader("X-RateLimit-Remaining"));
    verify(service).check("rl:ip:203.0.113.10");
    verify(service).check("rl:subject:verified-subject");
    verifyNoMoreInteractions(service);
  }

  @Test
  void exhaustedIpBucketStopsBeforeTheUserBucket() {
    request.setRemoteAddr("203.0.113.10");
    request.setUserPrincipal(() -> "verified-subject");
    when(service.check("rl:ip:203.0.113.10"))
        .thenReturn(new RateLimiterService.Decision(false, 20, 0, 3, 2000000000));
    assertThrows(
        RateLimitExceededException.class,
        () -> interceptor().preHandle(request, response, new Object()));
    verify(service).check("rl:ip:203.0.113.10");
    verifyNoMoreInteractions(service);
  }

  @Test
  void exhaustedUserBucketCannotBypassTheLimitByChangingIp() {
    request.setRemoteAddr("203.0.113.11");
    request.setUserPrincipal(() -> "verified-subject");
    when(service.check("rl:ip:203.0.113.11")).thenReturn(allowed());
    when(service.check("rl:subject:verified-subject"))
        .thenReturn(new RateLimiterService.Decision(false, 20, 0, 2, 2000000000));
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
    when(service.check("rl:ip:203.0.113.10")).thenThrow(outage);
    assertThrows(
        org.springframework.data.redis.RedisConnectionFailureException.class,
        () -> interceptor().preHandle(request, response, new Object()));
    var result = new com.tickit.exception.GlobalExceptionHandler().handleDependencyFailure(outage);
    assertEquals(503, result.getStatusCode().value());
    assertFalse(result.getBody().toString().contains("internal-provider-details"));
  }
}
