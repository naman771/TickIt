package com.tickit.interceptor;

import com.tickit.exception.RateLimitExceededException;
import com.tickit.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that enforces per-IP rate limiting
 * on configured endpoint patterns (see WebConfig).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiterInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String clientIp = extractClientIp(request);

        if (!rateLimiterService.isAllowedForIp(clientIp)) {
            throw new RateLimitExceededException(
                    "Too many requests from IP " + clientIp + ". Please try again later.");
        }

        // Optionally rate-limit by userId header (if present)
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null) {
            try {
                Long userId = Long.parseLong(userIdHeader);
                if (!rateLimiterService.isAllowedForUser(userId)) {
                    throw new RateLimitExceededException(
                            "Too many requests for user " + userId + ". Please try again later.");
                }
            } catch (NumberFormatException ignored) {
                // skip user-level limiting if header is invalid
            }
        }

        return true; // proceed
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
