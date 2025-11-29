package com.couchbase.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for Couchbase FHIR Server
 * - Protects /fhir/* endpoints with:
 *   1. API Tokens (user-generated from /tokens page)
 *   2. OAuth 2.0 JWT (SMART on FHIR from Spring Authorization Server)
 * - Protects /api/admin/* endpoints with JWT authentication (Admin UI only)
 * - OAuth 2.0 endpoints handled by AuthorizationServerConfig (@Order(1))
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Autowired
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;
    
    @Autowired
    private org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter jwtAuthenticationConverter;

    /**
     * Admin UI and general API filter chain
     * Handles custom JWT for /api/admin/* endpoints
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            // Use AntPathRequestMatcher so this chain applies regardless of MVC servlet mapping
            .securityMatcher("/api/**") // Only match /api/* paths
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Allow open access to Admin UI authentication
                .requestMatchers("/api/auth/**").permitAll()
                
                // Allow open access to initialization endpoints (needed before JWT exists)
                .requestMatchers("/api/admin/initialization/**").permitAll()
                
                // Protect all other Admin UI endpoints - require JWT
                .requestMatchers("/api/admin/**").authenticated()
                
                // Allow open access to other API endpoints
                .requestMatchers("/api/**").permitAll()
            )
            // Use same OAuth2 Resource Server JWT for admin endpoints now
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                .decoder(jwtDecoder)
                .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        
        return http.build();
    }

    /**
     * FHIR API filter chain
     * Handles OAuth2 JWT tokens (both SMART on FHIR and API tokens via client_credentials grant)
     * Allows public access to /fhir/metadata (CapabilityStatement)
     */
    @Bean
    @Order(3)
    public SecurityFilterChain fhirFilterChain(HttpSecurity http) throws Exception {
        // Custom matcher: in some deployments HAPI internally remaps the servlet path so Spring may
        // see just /Patient (etc.). We match if the original URI or servletPath starts with /fhir.
        RequestMatcher fhirRequestMatcher = request -> {
            String uri = request.getRequestURI();
            String servlet = request.getServletPath();
            return uri.startsWith("/fhir") || servlet.startsWith("/fhir");
        };

        http
            .securityMatcher(fhirRequestMatcher)
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Allow public access to metadata endpoint (FHIR CapabilityStatement)
                .requestMatchers("/fhir/metadata").permitAll()
                // Require authentication for all other FHIR endpoints
                .anyRequest().authenticated()
            )
            // OAuth 2.0 Resource Server - validates all OAuth2 JWT tokens
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            // Disable anonymous authentication
            .anonymous(anonymous -> anonymous.disable());
        
        return http.build();
    }

    /**
     * Default filter chain for all other endpoints
     * Includes form login for OAuth authentication and public SMART discovery
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
                
                // Allow open access to SMART configuration (FHIR discovery)
                .requestMatchers("/.well-known/smart-configuration", "/fhir/.well-known/smart-configuration").permitAll()
                
                // Allow login page and static resources
                .requestMatchers("/login", "/error", "/css/**", "/js/**").permitAll()
                
                // Allow all other requests (backwards compatibility)
                .anyRequest().permitAll()
            )
            // Enable form login for OAuth authentication
            // Uses SavedRequestAwareAuthenticationSuccessHandler to redirect back to original OAuth request
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            );
        
        return http.build();
    }
}
