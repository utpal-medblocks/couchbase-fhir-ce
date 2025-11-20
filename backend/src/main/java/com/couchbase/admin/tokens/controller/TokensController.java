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
            String type = (String) request.getOrDefault("type", "pat"); // "pat" or "client"
            @SuppressWarnings("unchecked")
            List<String> scopesList = (List<String>) request.get("scopes");
            String[] scopes = scopesList != null ? scopesList.toArray(new String[0]) : new String[0];

            if (appName == null || appName.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "App name is required"));
            }

            if (scopes.length == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "At least one scope is required"));
            }

            logger.info("🔑 Generating {} for user: {} (app: {})", type, userId, appName);
            Map<String, Object> result = tokenService.generateToken(userId, appName, scopes, userId, type);

            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            logger.error("❌ Error generating token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate token: " + e.getMessage()));
        }
    }

    /**
     * Get all tokens for the current user
     */
    @GetMapping
    public ResponseEntity<?> getMyTokens() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
            }

            logger.info("📋 Fetching tokens for user: {}", userId);
            List<Token> tokens = tokenService.getTokensByUserId(userId);
            return ResponseEntity.ok(tokens);
        } catch (Exception e) {
            logger.error("❌ Error fetching tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch tokens: " + e.getMessage()));
        }
    }

    /**
     * Get all tokens (admin only)
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllTokens() {
        try {
            // TODO: Add admin role check
            logger.info("📋 Fetching all tokens (admin)");
            List<Token> tokens = tokenService.getAllTokens();
            return ResponseEntity.ok(tokens);
        } catch (Exception e) {
            logger.error("❌ Error fetching all tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch tokens: " + e.getMessage()));
        }
    }

    /**
     * Get token by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTokenById(@PathVariable String id) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            logger.info("🔍 Fetching token: {}", id);
            return tokenService.getTokenById(id)
                    .map(token -> {
                        // Check if user owns this token
                        if (!token.getUserId().equals(userId)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("error", "You don't have access to this token"));
                        }
                        return ResponseEntity.ok(token);
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("❌ Error fetching token {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch token: " + e.getMessage()));
        }
    }

    /**
     * Revoke a token
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revokeToken(@PathVariable String id) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            // Check if user owns this token
            return tokenService.getTokenById(id)
                    .map(token -> {
                        if (!token.getUserId().equals(userId)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("error", "You don't have access to this token"));
                        }
                        logger.info("🗑️  Revoking token: {} (user: {})", id, userId);
                        tokenService.revokeToken(id);
                        return ResponseEntity.noContent().build();
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("❌ Error revoking token {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to revoke token: " + e.getMessage()));
        }
    }
}

