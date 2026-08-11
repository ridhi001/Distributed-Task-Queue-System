package com.jobcraft.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Arrays;

@Configuration
public class SecurityAndCorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Value("${app.security.api-key:dev-secret-api-key}")
    private String apiKey;

    @Value("${app.security.api-key-enabled:true}")
    private boolean apiKeyEnabled;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration() {
        FilterRegistrationBean<ApiKeyAuthFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ApiKeyAuthFilter(apiKey, apiKeyEnabled));
        registrationBean.addUrlPatterns("/api/jobs", "/api/jobs/*");
        registrationBean.setOrder(1);
        return registrationBean;
    }

    public static class ApiKeyAuthFilter extends OncePerRequestFilter {

        private final String expectedApiKey;
        private final boolean enabled;

        public ApiKeyAuthFilter(String expectedApiKey, boolean enabled) {
            this.expectedApiKey = expectedApiKey;
            this.enabled = enabled;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            // Allow OPTIONS preflight without auth
            if (HttpMethod.OPTIONS.name().equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            if (!enabled) {
                filterChain.doFilter(request, response);
                return;
            }

            String requestApiKey = request.getHeader("X-API-KEY");
            if (requestApiKey == null || requestApiKey.trim().isEmpty()) {
                requestApiKey = request.getHeader("x-api-key");
            }

            if (requestApiKey == null || !expectedApiKey.equals(requestApiKey.trim())) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing X-API-KEY header\"}");
                return;
            }

            filterChain.doFilter(request, response);
        }
    }
}
