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
        // Apply rate limiting to booking endpoints
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/api/bookings/**", "/api/holds/**");
    }

}
