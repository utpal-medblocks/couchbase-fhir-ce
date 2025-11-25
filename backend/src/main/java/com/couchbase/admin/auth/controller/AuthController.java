package com.couchbase.admin.auth.controller;

import com.couchbase.admin.auth.model.LoginRequest;
import com.couchbase.admin.auth.model.LoginResponse;
import com.couchbase.admin.auth.model.UserInfo;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.initialization.service.InitializationService;
import com.couchbase.client.java.Cluster;
import com.couchbase.common.config.AdminConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for Admin UI authentication
 * Handles login with credentials from config.yaml
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AdminConfig adminConfig;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserService userService;

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private InitializationService initializationService;

    // Simple cached presence flag to avoid repeating management API calls on every login.
    // null = unknown (not checked yet). True/false cached after first check.
    private volatile Boolean fhirBucketPresentCache = null;

    private boolean isFhirBucketPresent() {
        // Fast path: return cached value if already determined
        if (fhirBucketPresentCache != null) {
            return fhirBucketPresentCache;
        }
        try {
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) {
                fhirBucketPresentCache = false;
                return false;
            }
            // Lightweight management API call; throws if bucket missing
            cluster.buckets().getBucket("fhir");
            fhirBucketPresentCache = true;
            return true;
        } catch (Exception e) {
            fhirBucketPresentCache = false;
            return false;
        }
    }

    /**
     * Login endpoint for Admin UI
     * Validates credentials against config.yaml admin section
     * 
     * @param loginRequest Login credentials
     * @return JWT token and user info if successful, error otherwise
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        long startNs = System.nanoTime();
        logger.debug("🔐 [LOGIN] Request received for email='{}'", loginRequest.getEmail());
        if (loginRequest.getEmail() == null || loginRequest.getPassword() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(createErrorResponse("Email and password are required"));
        }

        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();

        // 1) Try authenticating against Admin.users ONLY if the fhir bucket exists AND is initialized
        // Check initialization status to avoid 10-second KV timeout on non-existent collections
        boolean shouldTryDatabase = false;
        if (isFhirBucketPresent()) {
            try {
                // Quick check: is the bucket initialized? (fast HTTP call, no KV timeout)
                var initStatus = initializationService.checkStatus("default");
                shouldTryDatabase = (initStatus.getStatus() == com.couchbase.admin.initialization.model.InitializationStatus.Status.READY);
            } catch (Exception e) {
                // If check fails, skip database lookup
                logger.debug("⏭️  [LOGIN] Cannot check initialization status, skipping database auth: {}", e.getMessage());
            }
        } else {
            logger.debug("⏭️  [LOGIN] Skipping Admin.users lookup; bucket 'fhir' not present yet (falling back to config.yaml)");
        }
        
        if (shouldTryDatabase) {
            try {
                java.util.Optional<User> userOpt = userService.getUserById(email);

                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    // Only support local auth users for Admin UI login
                    if ("local".equals(user.getAuthMethod())) {
                        boolean passwordOk = userService.verifyPassword(user.getId(), password);
                        if (passwordOk) {
                            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                            logger.debug("✅ [LOGIN] Authenticated via Admin.users (doc lookup) email='{}' in {} ms", email, elapsedMs);
                            String displayName = user.getUsername() != null ? user.getUsername() : user.getEmail();
                String[] scopes = user.getAllowedScopes() != null && user.getAllowedScopes().length > 0
                    ? user.getAllowedScopes() : new String[]{"system/*.*","user/*.*"};
                String token = issueAdminAccessToken(user.getEmail(), scopes);
                            UserInfo userInfo = new UserInfo(user.getEmail(), displayName, user.getRole(), user.getAllowedScopes());
                            return ResponseEntity.ok(new LoginResponse(token, userInfo));
                        }

                        // Local user exists but password doesn't match
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(createErrorResponse("Invalid email or password"));
                    }
                }
            } catch (Exception e) {
                // Silently fall through to config-based auth if database not available
                // This can occur during initialization races; we intentionally suppress.
            }
        }

        // 2) Fallback: Validate credentials against config.yaml
        // This allows login before bucket creation/initialization
        String configEmail = adminConfig.getEmail();
        String configPassword = adminConfig.getPassword();
        String configName = adminConfig.getName();

        if (configEmail.equals(email) && configPassword.equals(password)) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.debug("✅ [LOGIN] Authenticated via config.yaml fallback email='{}' in {} ms", email, elapsedMs);
            String[] scopes = new String[]{"system/*.*","user/*.*"};
            
            // Issue JWT token - signing key is always available (generated on startup)
            String token = issueAdminAccessToken(configEmail, scopes);
            
            UserInfo userInfo = new UserInfo(configEmail, configName, "admin", scopes);
            return ResponseEntity.ok(new LoginResponse(token, userInfo));
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        logger.debug("❌ [LOGIN] Failed authentication for email='{}' after {} ms", email, elapsedMs);
        // Invalid credentials
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(createErrorResponse("Invalid email or password"));
    }

    /**
     * Validate token endpoint (optional, for checking if token is still valid)
     * 
     * @param authHeader Authorization header with Bearer token
     * @return User info if token is valid
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("Invalid authorization header"));
        }

        String token = authHeader.substring(7);
        
        try {
            var jwt = jwtDecoder.decode(token);
            String subject = jwt.getSubject(); // clientId for client_credentials tokens
            // Attempt to map subject to token metadata if bucket present
            if (isFhirBucketPresent()) {
                // subject may be clientId (api-token-uuid). We don't store user role in JWT directly.
                // For now, return minimal info; full user context can be derived via token introspection in future.
                UserInfo userInfo = new UserInfo(subject, subject, "unknown", new String[]{});
                return ResponseEntity.ok(userInfo);
            }
            return ResponseEntity.ok(new UserInfo(subject, subject, "unknown", new String[]{}));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorResponse("Invalid or expired token"));
        }
    }

    /**
     * Helper method to create error response
     */
    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }

    /**
     * Issue an RS256 access token for the admin user directly using JwtEncoder.
     * Subject = user email; includes scope claim; 24h default validity.
     */
    private String issueAdminAccessToken(String email, String[] scopes) {
        try {
            java.time.Instant now = java.time.Instant.now();
            long hours = Long.parseLong(System.getProperty("oauth.token.expiry.hours",
                    System.getenv().getOrDefault("OAUTH_TOKEN_EXPIRY_HOURS", "24")));
            java.time.Instant exp = now.plus(java.time.Duration.ofHours(hours));
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .subject(email)
                    .issuedAt(now)
                    .expiresAt(exp)
                    .id(java.util.UUID.randomUUID().toString())  // Add JTI for revocation tracking
                    .claim("token_type", "admin")  // Explicit token type (hardening)
                    .claim("scope", String.join(" ", scopes))
                    .claim("email", email)
                    .build();
            return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        } catch (Exception e) {
            logger.error("❌ Failed to issue admin access token: {}", e.getMessage());
            return null;
        }
    }
}

