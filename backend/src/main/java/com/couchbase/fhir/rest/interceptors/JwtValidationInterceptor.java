package com.couchbase.fhir.rest.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.couchbase.admin.tokens.service.JwtTokenCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * HAPI FHIR Interceptor to validate Admin API JWT tokens against active token cache
 * 
 * This interceptor runs AFTER Spring Security has validated the JWT signature,
 * but BEFORE the FHIR operation is executed.
 * 
 * Token Types:
 * - OAuth tokens (from Spring Authorization Server): Skipped - managed by Spring
 * - Admin API tokens (from /api/admin/tokens): Validated against cache
 * 
 * Flow:
 * 1. Spring Security validates JWT signature and expiry
 * 2. This interceptor checks token type:
 *    - If OAuth token (has 'aud' claim) → Skip cache check
 *    - If Admin API token → Check if JTI is in active cache
 * 3. If active (or OAuth) → proceed to FHIR operation
 * 4. If not active → throw AuthenticationException (401)
 */
@Component
@Interceptor
public class JwtValidationInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(JwtValidationInterceptor.class);
    
    @Autowired
    private JwtTokenCacheService jwtTokenCacheService;
    
    /**
     * Validate JWT token on incoming requests
     * Runs after Spring Security authentication but before FHIR operation
     */
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
    public void validateJwtToken(RequestDetails requestDetails) {
        // Get authentication from Spring Security context
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            // No authentication - let Spring Security handle this
            return;
        }
        
        // Check if this is a JWT authentication (not username/password or other)
        if (!(authentication instanceof JwtAuthenticationToken)) {
            // Not a JWT token (might be session-based login) - skip validation
            logger.trace("[JWT-VALIDATION] Not a JWT authentication, skipping JTI check");
            return;
        }
        
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        Jwt jwt = jwtAuth.getToken();
        
        // Extract JTI (JWT ID) from token
        String jti = jwt.getClaim("jti");
        
        if (jti == null) {
            logger.warn("⚠️ [JWT-VALIDATION] JWT token missing 'jti' claim - cannot validate revocation status");
            // Allow request (backwards compatibility with tokens that don't have JTI)
            return;
        }
        
        // Check if this is an OAuth token (from Spring Authorization Server)
        // OAuth tokens have 'aud' (audience) claim, Admin API tokens don't
        String audience = jwt.getClaim("aud");
        if (audience != null) {
            logger.trace("[JWT-VALIDATION] OAuth token detected (aud: {}) - skipping cache validation (managed by Spring Authorization Server)", audience);
            // OAuth tokens are managed by Spring Authorization Server, not our cache
            return;
        }
        
        // This is an Admin API token - check if it's in the active cache
        if (!jwtTokenCacheService.isActive(jti)) {
            logger.warn("🚫 [JWT-VALIDATION] Admin API token with JTI '{}' is not active (revoked or expired)", jti);
            
            // Token has been revoked or doesn't exist
            throw new AuthenticationException("Token has been revoked or is no longer valid");
        }
        
        logger.trace("[JWT-VALIDATION] Admin API token JTI '{}' is active - proceeding with request", jti);
    }
}

