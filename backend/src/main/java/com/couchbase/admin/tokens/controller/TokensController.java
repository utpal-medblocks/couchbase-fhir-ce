package com.couchbase.admin.tokens.controller;

import com.couchbase.admin.tokens.model.Token;
import com.couchbase.admin.tokens.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for API token management
 */
@RestController
@RequestMapping("/api/admin/tokens")
public class TokensController {

    private static final Logger logger = LoggerFactory.getLogger(TokensController.class);

    private final TokenService tokenService;

    public TokensController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Generate a new API token
     * Request body: { "appName": "My App", "scopes": ["patient/*.read", "patient/*.write"] }
     */
    @PostMapping
    public ResponseEntity<?> generateToken(@RequestBody Map<String, Object> request) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : "unknown";

            String appName = (String) request.get("appName");
            @SuppressWarnings("unchecked")
            List<String> scopesList = (List<String>) request.get("scopes");
            String[] scopes = scopesList != null ? scopesList.toArray(new String[0]) : new String[0];

            if (appName == null || appName.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "App name is required"));
            }

            if (scopes.length == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "At least one scope is required"));
            }

            logger.info("🔑 Generating JWT access token for user: {} (app: {})", userId, appName);
            Map<String, Object> result = tokenService.generateToken(userId, appName, scopes, userId);

            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            logger.error("❌ Error generating token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate token: " + e.getMessage()));
        }
    }

    /**
     * Get tokens - returns all tokens if admin, otherwise only user's tokens
     */
    @GetMapping
    public ResponseEntity<?> getMyTokens() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
            }

            // Check if user is admin (has "admin" role in their authorities/claims)
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN") || 
                                     auth.getAuthority().equals("admin"));
            
            // For simplicity, also check if user has system/*.* scope (admin scope)
            if (!isAdmin) {
                isAdmin = authentication.getAuthorities().stream()
                        .anyMatch(auth -> auth.getAuthority().contains("system/*.*"));
            }

            if (isAdmin) {
                logger.info("📋 Fetching all tokens (admin user: {})", userId);
                List<Token> tokens = tokenService.getAllTokens();
                return ResponseEntity.ok(tokens);
            } else {
                logger.info("📋 Fetching tokens for user: {}", userId);
                List<Token> tokens = tokenService.getTokensByUserId(userId);
                return ResponseEntity.ok(tokens);
            }
        } catch (Exception e) {
            logger.error("❌ Error fetching tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch tokens: " + e.getMessage()));
        }
    }

    /**
     * Get all tokens (admin only) - DEPRECATED: Use GET / instead (auto-detects admin)
     * Kept for backwards compatibility
     */
    @Deprecated
    @GetMapping("/all")
    public ResponseEntity<?> getAllTokens() {
        try {
            logger.info("📋 Fetching all tokens (deprecated endpoint, use GET / instead)");
            List<Token> tokens = tokenService.getAllTokens();
            return ResponseEntity.ok(tokens);
        } catch (Exception e) {
            logger.error("❌ Error fetching all tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch tokens: " + e.getMessage()));
        }
    }

    // getTokenById removed - use getMyTokens() instead which returns all user's tokens

    /**
     * Revoke a token (marks as revoked and removes from cache)
     */
    @PutMapping("/{id}/revoke")
    public ResponseEntity<?> revokeToken(@PathVariable String id) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            logger.info("🚫 Revoking token: {} (requested by: {})", id, userId);
            tokenService.revokeToken(id);
            return ResponseEntity.noContent().build();
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("❌ Error revoking token {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to revoke token: " + e.getMessage()));
        }
    }

    /**
     * Permanently delete a token
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteToken(@PathVariable String id) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            logger.info("🗑️  Permanently deleting token: {} (requested by: {})", id, userId);
            tokenService.deleteToken(id);
            return ResponseEntity.noContent().build();
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("❌ Error deleting token {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete token: " + e.getMessage()));
        }
    }
}

