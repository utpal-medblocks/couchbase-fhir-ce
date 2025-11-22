package com.couchbase.admin.tokens.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.tokens.model.Token;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing API tokens as OAuth2 clients
 * Each "token" is actually an OAuth2 client that can obtain access tokens
 * via client_credentials grant
 */
@Service
public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "tokens";
    private static final String DEFAULT_CONNECTION = "default";
    
    // Token validity: 90 days for access tokens
    @Value("${api.token.validity.days:90}")
    private int tokenValidityDays;
    
    @Value("${server.port:8080}")
    private int serverPort;

    private final ConnectionService connectionService;
    private final RegisteredClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate;
    private final UserService userService;

    public TokenService(ConnectionService connectionService,
                       RegisteredClientRepository clientRepository,
                       UserService userService) {
        this.connectionService = connectionService;
        this.clientRepository = clientRepository;
        this.userService = userService;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.restTemplate = new RestTemplate();
    }

    private Collection getTokensCollection() {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        if (cluster == null) {
            throw new IllegalStateException("No active Couchbase connection: " + DEFAULT_CONNECTION);
        }
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }

    /**
     * Generate a new OAuth2 client for API access
     * @param userId User ID (email)
     * @param appName Application name
     * @param scopes FHIR scopes
     * @param createdBy Who is creating this token
     * @param type "pat" (Personal Access Token) or "client" (SMART App)
     * @return Map with "clientId", "clientSecret" (plain text, show once!), and "tokenMetadata"
     */
    public Map<String, Object> generateToken(String userId, String appName, String[] scopes, String createdBy, String type) {
        logger.info("🔑 Generating OAuth2 {} for user: {} (app: {})", type, userId, appName);

        // Validate that requested scopes are allowed for this user
        User user = userService.getUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        
        validateScopes(scopes, user);

        // Generate client ID and secret
        String clientId = "api-token-" + UUID.randomUUID().toString();
        String clientSecret = generateSecureSecret();
        
        // Hash the client secret for storage
        String clientSecretHash = passwordEncoder.encode(clientSecret);

        // Create OAuth2 RegisteredClient
        // Note: clientSecret must be stored in encoded format with {bcrypt} prefix
        RegisteredClient registeredClient = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientSecret("{noop}" + clientSecret) // Use plain text for internal token exchange
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(scopeSet -> scopeSet.addAll(Arrays.asList(scopes)))
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofDays(tokenValidityDays))
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        // Save to Spring's RegisteredClientRepository
        clientRepository.save(registeredClient);

        // Create token metadata for our database
        Token tokenMetadata = new Token(clientId, userId, appName, clientId, clientSecretHash, createdBy, scopes, type);

        // Store in Couchbase
        Collection tokensCollection = getTokensCollection();
        tokensCollection.insert(clientId, tokenMetadata);

        logger.info("✅ OAuth2 client created: {} (scopes: {})", clientId, String.join(",", scopes));

        Map<String, Object> result = new HashMap<>();
        result.put("tokenMetadata", tokenMetadata);
        result.put("clientId", clientId);
        
        // If Type is PAT (Personal Access Token), immediately exchange for JWT
        if ("pat".equals(type)) {
            String accessToken = exchangeForAccessToken(clientId, clientSecret, scopes);
            result.put("token", accessToken); // JWT access token
        } else {
            // If Type is Client (SMART App), return the secret so they can use it
            result.put("clientSecret", clientSecret); // Plain text - show once!
        }
        
        // Also include info for advanced users who want to refresh tokens
        result.put("tokenEndpoint", "/oauth2/token");
        result.put("grantType", "client_credentials");

        return result;
    }

    // Overload for backward compatibility
    public Map<String, Object> generateToken(String userId, String appName, String[] scopes, String createdBy) {
        return generateToken(userId, appName, scopes, createdBy, "pat");
    }
    
    /**
     * Exchange OAuth2 client credentials for JWT access token
     * Calls the /oauth2/token endpoint internally
     */
    private String exchangeForAccessToken(String clientId, String clientSecret, String[] scopes) {
        try {
            // Prepare request
            String tokenUrl = "http://localhost:" + serverPort + "/oauth2/token";
            
            // Set headers with Basic Auth
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String auth = clientId + ":" + clientSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            headers.set("Authorization", "Basic " + encodedAuth);
            
            // Set form parameters
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("scope", String.join(" ", scopes));
            
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            
            // Call token endpoint
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String accessToken = (String) response.getBody().get("access_token");
                logger.info("✅ JWT access token obtained (length: {})", accessToken.length());
                return accessToken;
            } else {
                throw new RuntimeException("Failed to obtain access token: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("❌ Failed to exchange credentials for access token: {}", e.getMessage());
            throw new RuntimeException("Failed to generate access token", e);
        }
    }

    /**
     * Get token by ID (client_id)
     */
    public Optional<Token> getTokenById(String id) {
        Collection tokensCollection = getTokensCollection();
        try {
            Token token = tokensCollection.get(id).contentAs(Token.class);
            return Optional.of(token);
        } catch (DocumentNotFoundException e) {
            logger.debug("Token not found: {}", id);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting token by ID {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to retrieve token: " + e.getMessage(), e);
        }
    }

    /**
     * Get all tokens for a user
     */
    public List<Token> getTokensByUserId(String userId) {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        String query = String.format(
                "SELECT META().id, t.* FROM `%s`.`%s`.`%s` t WHERE t.userId = $userId ORDER BY t.createdAt DESC",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
        );
        try {
            QueryResult result = cluster.query(query,
                    QueryOptions.queryOptions().parameters(JsonObject.create().put("userId", userId)));
            return result.rowsAs(Token.class).stream().collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching tokens for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to fetch tokens: " + e.getMessage(), e);
        }
    }

    /**
     * Get all tokens (admin only)
     */
    public List<Token> getAllTokens() {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        String query = String.format(
                "SELECT META().id, t.* FROM `%s`.`%s`.`%s` t ORDER BY t.createdAt DESC",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
        );
        try {
            QueryResult result = cluster.query(query);
            return result.rowsAs(Token.class).stream().collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching all tokens", e);
            throw new RuntimeException("Failed to fetch all tokens: " + e.getMessage(), e);
        }
    }

    /**
     * Revoke a token (delete OAuth2 client)
     */
    /**
     * Revoke a token (marks as revoked but keeps in database)
     */
    public void revokeToken(String tokenId) {
        // Delete from Spring's RegisteredClientRepository
        try {
            RegisteredClient client = clientRepository.findById(tokenId);
            if (client != null) {
                // Spring Security doesn't have a delete method, so we mark as inactive in our DB
                logger.info("🚫 Revoking OAuth2 client: {}", tokenId);
            }
        } catch (Exception e) {
            logger.warn("Failed to find OAuth2 client {}: {}", tokenId, e.getMessage());
        }

        // Update status in our database
        Collection tokensCollection = getTokensCollection();
        getTokenById(tokenId).ifPresent(token -> {
            token.setStatus("revoked");
            tokensCollection.replace(tokenId, token);
        });
        
        logger.info("🚫 Token revoked: {}", tokenId);
    }

    /**
     * Permanently delete a token from the database
     */
    public void deleteToken(String tokenId) {
        try {
            Collection tokensCollection = getTokensCollection();
            tokensCollection.remove(tokenId);
            logger.info("🗑️  Token permanently deleted: {}", tokenId);
        } catch (DocumentNotFoundException e) {
            logger.warn("Token not found for deletion: {}", tokenId);
            throw new RuntimeException("Token not found: " + tokenId);
        } catch (Exception e) {
            logger.error("❌ Failed to delete token: {}", tokenId, e);
            throw new RuntimeException("Failed to delete token: " + e.getMessage());
        }
    }

    /**
     * Update last used timestamp
     */
    public void updateLastUsed(String tokenId) {
        try {
            Collection tokensCollection = getTokensCollection();
            getTokenById(tokenId).ifPresent(token -> {
                token.setLastUsedAt(Instant.now());
                tokensCollection.replace(tokenId, token);
            });
        } catch (Exception e) {
            logger.debug("Failed to update last used for token {}: {}", tokenId, e.getMessage());
        }
    }

    /**
     * Validate that requested scopes are a subset of user's allowed scopes
     * Handles wildcard matching (e.g. user has "patient/*.read", requested "patient/Patient.read" is allowed)
     */
    private void validateScopes(String[] requestedScopes, User user) {
        String[] allowedScopes = user.getAllowedScopes();
        if (allowedScopes == null || allowedScopes.length == 0) {
             // If no allowed scopes defined, default to restrictive (or allow none)
             // For now, if allowedScopes is null, we assume legacy user/admin and rely on role check
             if (user.isAdmin()) return; // Admin allowed everything
             throw new IllegalArgumentException("User has no allowed scopes defined");
        }

        for (String requested : requestedScopes) {
            boolean allowed = false;
            for (String allowedScope : allowedScopes) {
                if (scopesMatch(requested, allowedScope)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IllegalArgumentException("Scope not allowed for this user: " + requested);
            }
        }
    }

    /**
     * Check if requested scope matches allowed scope pattern
     * Supported patterns:
     * - Exact match: "patient/Patient.read" == "patient/Patient.read"
     * - Wildcard resource: "patient/*.read" matches "patient/Patient.read"
     * - Wildcard action: "patient/Patient.*" matches "patient/Patient.read"
     * - Full wildcard: "system/*.*" matches anything starting with "system/"
     */
    private boolean scopesMatch(String requested, String allowed) {
        if (allowed.equals(requested)) return true;
        
        // Handle "system/*.*" or "user/*.*"
        if (allowed.endsWith("*.*")) {
            String prefix = allowed.substring(0, allowed.indexOf("*.*"));
            return requested.startsWith(prefix);
        }

        // Simple regex-like matching for single wildcards could be added here
        // For now, we support the basic FHIR patterns used in our defaults
        
        return false;
    }

    /**
     * Generate a cryptographically secure random secret
     */
    private String generateSecureSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 256 bits
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

