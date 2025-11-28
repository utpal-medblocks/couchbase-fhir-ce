package com.couchbase.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
public class CorsConfig {
    
    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;
    
    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;
    
    @Value("${cors.allowed-headers:*}")
    private String allowedHeaders;
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Split and set allowed origins
        String[] origins = allowedOrigins.split(",");
        for (String origin : origins) {
            origin = origin.trim();
            // Support wildcard patterns like *.cbfhir.com
            if (origin.equals("*")) {
                // Cannot use * with credentials - use allowedOriginPatterns instead
                configuration.addAllowedOriginPattern("*");
            } else {
                configuration.addAllowedOrigin(origin);
            }
        }
        
        // Split and set allowed methods
        configuration.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        
        // Set allowed headers
        if ("*".equals(allowedHeaders)) {
            configuration.addAllowedHeader("*");
        } else {
            configuration.setAllowedHeaders(Arrays.asList(allowedHeaders.split(",")));
        }
        
        // Allow credentials
        configuration.setAllowCredentials(true);
        
        // Set max age for preflight requests
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Register CORS for API endpoints
        source.registerCorsConfiguration("/api/**", configuration);
        // Register CORS for OAuth 2.0 endpoints (SMART on FHIR)
        source.registerCorsConfiguration("/oauth2/**", configuration);
        source.registerCorsConfiguration("/.well-known/**", configuration);
        // Register CORS for FHIR endpoints
        source.registerCorsConfiguration("/fhir/**", configuration);
        
        return source;
    }
} 