package com.couchbase.fhir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Custom JWT Granted Authorities Converter that safely handles scope claims
 * regardless of their type (String or Collection)
 * 
 * This converter extracts scopes from JWT tokens and converts them to Spring Security authorities.
 * It handles both:
 * - Space-separated string: "scope1 scope2 scope3"
 * - Collection: ["scope1", "scope2", "scope3"]
 */
public class CustomJwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    
    private static final Logger logger = LoggerFactory.getLogger(CustomJwtGrantedAuthoritiesConverter.class);
    
    private static final String SCOPE_CLAIM_NAME = "scope";
    private static final String AUTHORITY_PREFIX = "SCOPE_";
    
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        logger.debug("🔍 [JWT-AUTHORITIES] Converting JWT to authorities");
        
        Object scopeClaim = jwt.getClaim(SCOPE_CLAIM_NAME);
        
        if (scopeClaim == null) {
            logger.debug("⚠️ [JWT-AUTHORITIES] No scope claim found in JWT");
            return Collections.emptyList();
        }
        
        logger.debug("🔍 [JWT-AUTHORITIES] scope claim type: {}, value: {}", 
            scopeClaim.getClass().getName(), scopeClaim);
        
        List<String> scopes = extractScopes(scopeClaim);
        logger.debug("✅ [JWT-AUTHORITIES] Extracted {} scopes: {}", scopes.size(), scopes);
        
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority(AUTHORITY_PREFIX + scope));
        }
        
        logger.debug("✅ [JWT-AUTHORITIES] Created {} authorities", authorities.size());
        return authorities;
    }
    
    /**
     * Extract scopes from the claim, handling both String and Collection types
     */
    private List<String> extractScopes(Object scopeClaim) {
        // Handle String (space-separated)
        if (scopeClaim instanceof String) {
            String scopeString = (String) scopeClaim;
            logger.debug("🔍 [JWT-AUTHORITIES] Parsing scope as String: '{}'", scopeString);
            
            if (scopeString.trim().isEmpty()) {
                return Collections.emptyList();
            }
            
            return List.of(scopeString.split("\\s+"));
        }
        
        // Handle Collection (already parsed as array/list)
        if (scopeClaim instanceof Collection) {
            logger.debug("🔍 [JWT-AUTHORITIES] Parsing scope as Collection");
            Collection<?> scopeCollection = (Collection<?>) scopeClaim;
            
            List<String> scopes = new ArrayList<>();
            for (Object item : scopeCollection) {
                if (item != null) {
                    scopes.add(item.toString());
                }
            }
            return scopes;
        }
        
        // Unexpected type
        logger.warn("⚠️ [JWT-AUTHORITIES] Unexpected scope claim type: {}", scopeClaim.getClass().getName());
        return Collections.emptyList();
    }
}

