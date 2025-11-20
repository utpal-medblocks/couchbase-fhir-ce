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
public class OAuth2TokenService {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2TokenService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "tokens";
    private static final String DEFAULT_CONNECTION = "default";
    
    // Token validity: 90 days for access tokens
    @Value("${api.token.validity.days:90}")
    private int tokenValidityDays;

    private final ConnectionService connectionService;
    private final RegisteredClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    public OAuth2TokenService(ConnectionService connectionService,
                              RegisteredClientRepository clientRepository) {
        this.connectionService = connectionService;
        this.clientRepository = clientRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
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
     * @return Map with "clientId", "clientSecret" (plain text, show once!), and "tokenMetadata"
     */
    public Map<String, Object> generateToken(String userId, String appName, String[] scopes, String createdBy) {
        logger.info("🔑 Generating OAuth2 client for user: {} (app: {})", userId, appName);

        // Generate client ID and secret
        String clientId = "api-token-" + UUID.randomUUID().toString();
        String clientSecret = generateSecureSecret();
        
        // Hash the client secret for storage
        String clientSecretHash = passwordEncoder.encode(clientSecret);

        // Create OAuth2 RegisteredClient
        RegisteredClient registeredClient = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientSecret("{bcrypt}" + clientSecretHash) // Spring Security format
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
        Token tokenMetadata = new Token(clientId, userId, appName, clientId, clientSecretHash, createdBy, scopes);

        // Store in Couchbase
        Collection tokensCollection = getTokensCollection();
        tokensCollection.insert(clientId, tokenMetadata);

        logger.info("✅ OAuth2 client created: {} (scopes: {})", clientId, String.join(",", scopes));

        // Return client credentials (show once!) and metadata
        Map<String, Object> result = new HashMap<>();
        result.put("clientId", clientId);
        result.put("clientSecret", clientSecret); // Plain text - show once!
        result.put("tokenMetadata", tokenMetadata);
        result.put("tokenEndpoint", "/oauth2/token");
        result.put("grantType", "client_credentials");

        return result;
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
    public void revokeToken(String tokenId) {
        // Delete from Spring's RegisteredClientRepository
        try {
            RegisteredClient client = clientRepository.findById(tokenId);
            if (client != null) {
                // Spring Security doesn't have a delete method, so we mark as inactive in our DB
                logger.info("🗑️  Revoking OAuth2 client: {}", tokenId);
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
        
        logger.info("🗑️  Token revoked: {}", tokenId);
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
     * Generate a cryptographically secure random secret
     */
    private String generateSecureSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 256 bits
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

