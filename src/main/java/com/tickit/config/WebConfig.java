package com.tickit.config;

import com.tickit.interceptor.RateLimiterInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

  private final RateLimiterInterceptor rateLimiterInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Protect catalog reads and future API handlers by default. Provider callbacks
    // use signature validation and idempotency, independently of customer quotas.
    registry
        .addInterceptor(rateLimiterInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/payments/webhook");
  }
}
