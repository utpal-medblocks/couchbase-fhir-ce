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
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

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
        
        Set<GrantedAuthority> authorities = new HashSet<>();

        // 1) Extract OAuth2 scopes (space-separated string or collection)
        Object scopeClaim = jwt.getClaim(SCOPE_CLAIM_NAME);
        if (scopeClaim != null) {
            logger.debug("🔍 [JWT-AUTHORITIES] scope claim type: {}, value: {}", 
                scopeClaim.getClass().getName(), scopeClaim);
            List<String> scopes = extractScopes(scopeClaim);
            logger.debug("✅ [JWT-AUTHORITIES] Extracted {} scopes: {}", scopes.size(), scopes);
            for (String scope : scopes) {
                if (scope != null && !scope.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(AUTHORITY_PREFIX + scope));
                }
            }
        } else {
            logger.debug("🔍 [JWT-AUTHORITIES] No 'scope' claim present, checking 'scp' claim");
            Object scpClaim = jwt.getClaim("scp");
            if (scpClaim != null) {
                List<String> scopes = extractScopes(scpClaim);
                for (String scope : scopes) {
                    if (scope != null && !scope.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(AUTHORITY_PREFIX + scope));
                    }
                }
            }
        }

        // 2) Extract Keycloak realm roles (realm_access.roles)
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map) {
            try {
                Object rolesObj = ((Map<?, ?>) realmAccess).get("roles");
                if (rolesObj instanceof Collection) {
                    for (Object r : (Collection<?>) rolesObj) {
                        if (r != null) {
                            String role = r.toString();
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("⚠️ [JWT-AUTHORITIES] Failed to parse realm_access.roles: {}", e.getMessage());
            }
        }

        // 3) Extract Keycloak client/resource roles (resource_access.{client}.roles)
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map) {
            try {
                Map<?, ?> resMap = (Map<?, ?>) resourceAccess;
                for (Map.Entry<?, ?> entry : resMap.entrySet()) {
                    Object client = entry.getKey();
                    Object clientObj = entry.getValue();
                    if (clientObj instanceof Map) {
                        Object rolesObj = ((Map<?, ?>) clientObj).get("roles");
                        if (rolesObj instanceof Collection) {
                            for (Object r : (Collection<?>) rolesObj) {
                                if (r != null) {
                                    String role = r.toString();
                                    // Prefix client name to role to avoid collisions
                                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("⚠️ [JWT-AUTHORITIES] Failed to parse resource_access roles: {}", e.getMessage());
            }
        }

        logger.debug("✅ [JWT-AUTHORITIES] Created {} authorities", authorities.size());
        return new ArrayList<>(authorities);
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

            String[] parts = scopeString.split("\\s+");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                if (p != null && !p.isBlank()) list.add(p);
            }
            return list;
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
        if (scopeClaim != null) {
            logger.warn("⚠️ [JWT-AUTHORITIES] Unexpected scope claim type: {}", scopeClaim.getClass().getName());
        }
        return Collections.emptyList();
    }
}

