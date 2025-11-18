package com.couchbase.common.config;

import com.couchbase.admin.auth.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for Couchbase FHIR Server
 * - Protects /fhir/* endpoints with OAuth 2.0 (SMART on FHIR)
 * - Protects /api/admin/* endpoints with JWT authentication (Admin UI only)
 * - OAuth 2.0 endpoints handled by AuthorizationServerConfig (@Order(1))
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Admin UI and general API filter chain
     * Handles custom JWT for /api/admin/* endpoints
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**") // Only match /api/* paths
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Protect Admin UI endpoints - require custom JWT
                .requestMatchers("/api/admin/**").authenticated()
                
                // Allow open access to Admin UI authentication
                .requestMatchers("/api/auth/**").permitAll()
                
                // Allow open access to other API endpoints
                .requestMatchers("/api/**").permitAll()
            )
            // Add Admin UI JWT filter for custom JWT validation
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }

    /**
     * FHIR API filter chain
     * Handles OAuth 2.0 JWT for /fhir/* endpoints
     */
    @Bean
    @Order(3)
    public SecurityFilterChain fhirFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/fhir/**") // Only match /fhir/* paths
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/fhir/**").authenticated()
            )
            // OAuth 2.0 Resource Server for FHIR API
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults())
            );
        
        return http.build();
    }

    /**
     * Default filter chain for all other endpoints
     * Includes form login for OAuth authentication
     */
    @Bean
    @Order(4)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF enabled for login form (required by Spring Security form login)
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**") // Disable for REST APIs
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(authz -> authz
                // Allow open access to actuator endpoints (health, metrics)
                .requestMatchers("/actuator/**").permitAll()
                
                // Allow login page and static resources
                .requestMatchers("/login", "/error", "/css/**", "/js/**").permitAll()
                
                // Allow all other requests (backwards compatibility)
                .anyRequest().permitAll()
            )
            // Enable form login for OAuth authentication
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            );
        
        return http.build();
    }
}
