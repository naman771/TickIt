package com.tickit.interceptor;

import com.tickit.config.AuthSettings;
import com.tickit.exception.RateLimitExceededException;
import com.tickit.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Rate limits every mapped API handler except the signed provider webhook. */
@Component
@RequiredArgsConstructor
public class RateLimiterInterceptor implements HandlerInterceptor {
  private final RateLimiterService rateLimiterService;
  private final AuthSettings authSettings;

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) return true;
    // Never read client-supplied forwarding headers here. Trusted ingress/container
    // configuration must resolve proxy addresses before this boundary.
    var decision = rateLimiterService.check("rl:ip:" + request.getRemoteAddr());
    var principal = request.getUserPrincipal();
    if (decision.allowed() && principal != null && !authSettings.isTest()) {
      decision = decision.restrict(rateLimiterService.check("rl:subject:" + principal.getName()));
    }
    response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
    response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
    response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
    if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfterSeconds());
    return true;
  }
}
