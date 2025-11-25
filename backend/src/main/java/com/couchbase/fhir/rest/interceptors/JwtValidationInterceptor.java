package com.couchbase.fhir.rest.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.admin.tokens.service.JwtTokenCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * HAPI FHIR Interceptor to validate Admin/API JWT tokens against active token cache
 * 
 * This interceptor runs AFTER Spring Security has validated the JWT signature,
 * but BEFORE the FHIR operation is executed.
 * 
 * Token Types (identified by token_type claim):
 * - "oauth": OAuth tokens from Spring Authorization Server - skipped (managed by Spring)
 * - "admin": Admin UI login tokens - validated against cache
 * - "api": API tokens from /tokens page - validated against cache
 * 
 * Token Type Detection (hardened):
 * 1. Primary: Check token_type claim (explicit)
 * 2. Fallback: Check aud claim (backward compatibility)
 * 
 * Flow:
 * 1. Spring Security validates JWT signature and expiry
 * 2. This interceptor checks token type using token_type claim
 * 3. If OAuth token → Skip cache check (Spring manages these)
 * 4. If Admin/API token → Check if JTI is in active cache
 * 5. If active → proceed to FHIR operation
 * 6. If not active → throw ForbiddenOperationException (403)
 */
@Component
@Interceptor
public class JwtValidationInterceptor extends ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter {

  private static final Logger logger = LoggerFactory.getLogger(JwtValidationInterceptor.class);

  @Autowired
  private JwtTokenCacheService jwtTokenCacheService;

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
  public void validateJwtToken(RequestDetails rd) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return; // Spring will handle 401/403
    }
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      logger.trace("[JWT-VALIDATION] Not a JWT auth, skipping JTI check");
      return;
    }

    Jwt jwt = jwtAuth.getToken();

    // Use typed accessors (never cast getClaim results)
    String jti = jwt.getId();                         // ✅ JTI
    String tokenType = jwt.getClaimAsString("token_type");  // ✅ Explicit token type
    java.util.List<String> aud = jwt.getAudience();   // ✅ Audience as List

    if (jti == null) {
      logger.warn("⚠️ [JWT-VALIDATION] Token missing 'jti' claim");
      return; // backward compatibility
    }

    // Determine token type (prefer explicit token_type claim)
    boolean isOAuthToken = false;
    if (tokenType != null) {
      // Explicit token_type claim (hardened approach)
      isOAuthToken = "oauth".equals(tokenType);
      logger.trace("[JWT-VALIDATION] Token type: {} (from token_type claim)", tokenType);
    } else {
      // Fallback: infer from audience claim (backward compatibility)
      isOAuthToken = (aud != null && !aud.isEmpty());
      logger.trace("[JWT-VALIDATION] Token type inferred from aud: {} (fallback method)", 
        isOAuthToken ? "oauth" : "admin/api");
    }

    if (isOAuthToken) {
      logger.trace("[JWT-VALIDATION] OAuth token - skipping cache validation (managed by Spring Authorization Server)");
      return; // OAuth tokens managed by Spring Authorization Server
    }

    // Admin API token path: check local revocation cache
    if (!jwtTokenCacheService.isActive(jti)) {
      logger.warn("🚫 [JWT-VALIDATION] Admin API token JTI '{}' is not active", jti);
      throw new ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException("Token revoked or invalid");
    }

    logger.trace("[JWT-VALIDATION] Admin API token JTI '{}' is active", jti);
  }
}

