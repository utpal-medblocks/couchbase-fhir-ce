package com.couchbase.admin.tokens.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.tokens.model.Token;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.couchbase.admin.users.model.User;
import com.couchbase.admin.users.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/**
 * Service for managing JWT access tokens
 * 
 * Tokens are issued as JWTs signed with the OAuth signing key.
 * Token metadata is stored in fhir.Admin.tokens collection for tracking and revocation.
 * Active token JTIs are cached in JwtTokenCacheService for fast validation.
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

    private final ConnectionService connectionService;
    private final UserService userService;
    private final JwtEncoder jwtEncoder;
    private final JwtTokenCacheService jwtTokenCacheService;

    public TokenService(ConnectionService connectionService,
                       UserService userService,
                       JwtEncoder jwtEncoder,
                       JwtTokenCacheService jwtTokenCacheService) {
        this.connectionService = connectionService;
        this.userService = userService;
        this.jwtEncoder = jwtEncoder;
        this.jwtTokenCacheService = jwtTokenCacheService;
    }

    private Collection getTokensCollection() {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        if (cluster == null) {
            throw new IllegalStateException("No active Couchbase connection: " + DEFAULT_CONNECTION);
        }
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }

    /**
     * Generate a new JWT access token
     * @param userId User ID (email)
     * @param appName Application name
     * @param scopes FHIR scopes
     * @param createdBy Who is creating this token
     * @return Map with "token" (JWT string) and "tokenMetadata" (Token object)
     */
    public Map<String, Object> generateToken(String userId, String appName, String[] scopes, String createdBy) {
        logger.info("🔑 Generating JWT access token for user: {} (app: {})", userId, appName);

        // Validate that requested scopes are allowed for this user
        User user = userService.getUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        
        validateScopes(scopes, user);

        // Generate unique JWT ID
        String jti = UUID.randomUUID().toString();
        
        // Calculate expiration
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(tokenValidityDays));
        
        // Build JWT claims
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(jti)  // JWT ID for revocation tracking
                .subject(userId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("token_type", "api")  // Explicit token type (API tokens from /tokens page)
                .claim("scope", String.join(" ", scopes))
                .claim("email", userId)
                .claim("appName", appName)
                .build();
        
        // Encode JWT
        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        logger.info("✅ JWT encoded successfully (jti: {}, length: {} chars)", jti, jwt.length());
        
        // Create token metadata
        Token tokenMetadata = new Token(jti, userId, appName, scopes, expiresAt, createdBy);
        
        // Store metadata in Couchbase
        Collection tokensCollection = getTokensCollection();
        tokensCollection.insert(tokenMetadata.getId(), tokenMetadata);
        logger.info("✅ Token metadata stored in fhir.Admin.tokens (id: {})", tokenMetadata.getId());
        
        // Add JTI to active cache
        jwtTokenCacheService.addToken(jti);
        logger.debug("✅ JTI added to active cache");

        // Return result
        Map<String, Object> result = new HashMap<>();
        result.put("token", jwt); // The actual JWT to use in API calls
        result.put("tokenMetadata", tokenMetadata); // Metadata for display
        result.put("expiresAt", expiresAt.toString());
        result.put("validityDays", tokenValidityDays);

        return result;
    }

    /**
     * Validate that requested scopes are allowed for the user
     */
    private void validateScopes(String[] requestedScopes, User user) {
        Set<String> allowedScopes = new HashSet<>(Arrays.asList(user.getAllowedScopes()));
        
        for (String scope : requestedScopes) {
            if (!allowedScopes.contains(scope)) {
                throw new IllegalArgumentException(
                    String.format("Scope '%s' not allowed for user '%s'. Allowed scopes: %s", 
                        scope, user.getId(), String.join(", ", allowedScopes)));
            }
        }
    }

    /**
     * Get all tokens for a user
     */
    public List<Token> getTokensByUserId(String userId) {
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                return List.of();
            }

            String sql = String.format(
                "SELECT t.* FROM `%s`.`%s`.`%s` t WHERE t.userId = $userId AND t.type = 'jwt_access_token'",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
            );

            QueryOptions options = QueryOptions.queryOptions()
                .parameters(JsonObject.create().put("userId", userId));

            var result = cluster.query(sql, options);
            return result.rowsAs(Token.class);

        } catch (Exception e) {
            logger.error("Error fetching tokens for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get all tokens (admin only)
     */
    public List<Token> getAllTokens() {
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                return List.of();
            }

            String sql = String.format(
                "SELECT t.* FROM `%s`.`%s`.`%s` t WHERE t.type = 'jwt_access_token' ORDER BY t.createdAt DESC",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
            );

            var result = cluster.query(sql);
            return result.rowsAs(Token.class);

        } catch (Exception e) {
            logger.error("Error fetching all tokens: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Revoke a token (sets status to "revoked" and removes from cache)
     */
    public void revokeToken(String tokenId) {
        logger.info("🚫 Revoking token: {}", tokenId);
        
        try {
            Collection tokensCollection = getTokensCollection();
            
            // Get current token
            var result = tokensCollection.get(tokenId);
            Token token = result.contentAs(Token.class);
            
            // Update status to revoked
            token.setStatus("revoked");
            tokensCollection.replace(tokenId, token);
            
            // Remove JTI from cache
            jwtTokenCacheService.removeToken(token.getJti());
            
            logger.info("✅ Token revoked: {} (jti: {})", tokenId, token.getJti());
            
        } catch (DocumentNotFoundException e) {
            logger.warn("⚠️ Token not found for revocation: {}", tokenId);
            throw new IllegalArgumentException("Token not found: " + tokenId);
        } catch (Exception e) {
            logger.error("❌ Failed to revoke token {}: {}", tokenId, e.getMessage());
            throw new RuntimeException("Failed to revoke token", e);
        }
    }

    /**
     * Delete a token permanently
     */
    public void deleteToken(String tokenId) {
        logger.info("🗑️ Deleting token: {}", tokenId);
        
        try {
            Collection tokensCollection = getTokensCollection();
            
            // Get token to extract JTI
            var result = tokensCollection.get(tokenId);
            Token token = result.contentAs(Token.class);
            String jti = token.getJti();
            
            // Delete from database
            tokensCollection.remove(tokenId);
            
            // Remove JTI from cache
            jwtTokenCacheService.removeToken(jti);
            
            logger.info("✅ Token deleted: {} (jti: {})", tokenId, jti);
            
        } catch (DocumentNotFoundException e) {
            logger.warn("⚠️ Token not found for deletion: {}", tokenId);
            throw new IllegalArgumentException("Token not found: " + tokenId);
        } catch (Exception e) {
            logger.error("❌ Failed to delete token {}: {}", tokenId, e.getMessage());
            throw new RuntimeException("Failed to delete token", e);
        }
    }
}
