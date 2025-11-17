package com.couchbase.common.config;

import com.couchbase.admin.auth.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for Couchbase FHIR Server
 * - Protects /api/admin/* endpoints with JWT authentication (Admin UI only)
 * - Allows open access to /fhir/* (FHIR API), /actuator/* (metrics), /api/auth/* (login)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Protect Admin UI endpoints - require JWT authentication
                .requestMatchers("/api/admin/**").authenticated()
                
                // Allow open access to authentication endpoints
                .requestMatchers("/api/auth/**").permitAll()
                
                // Allow open access to FHIR API (no authentication for Step 1)
                .requestMatchers("/fhir/**").permitAll()
                
                // Allow open access to actuator endpoints (health, metrics)
                .requestMatchers("/actuator/**").permitAll()
                
                // Allow all other requests (backwards compatibility)
                .anyRequest().permitAll()
            )
            // Add JWT filter before Spring Security's authentication filter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
