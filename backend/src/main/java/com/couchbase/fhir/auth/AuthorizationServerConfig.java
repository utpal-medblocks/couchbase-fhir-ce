package com.couchbase.fhir.auth;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth 2.0 Authorization Server Configuration for SMART on FHIR
 * 
 * Provides OAuth 2.0 endpoints:
 * - /oauth2/authorize - Authorization endpoint
 * - /oauth2/token - Token endpoint
 * - /oauth2/introspect - Token introspection
 * - /oauth2/revoke - Token revocation
 * - /.well-known/oauth-authorization-server - Server metadata
 * - /oauth2/jwks - JSON Web Key Set
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationServerConfig.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "config";
    private static final String JWK_DOCUMENT_ID = "oauth-signing-key";
    private static final String DEFAULT_CONNECTION = "default";

    @Autowired
    private ConnectionService connectionService;
    
    // Cache the RSA key to avoid repeated Couchbase lookups
    private volatile RSAKey cachedRsaKey = null;
    
    /**
     * Invalidate the cached RSA key to force reload from Couchbase
     * Called after initialization creates a new key
     */
    public void invalidateKeyCache() {
        logger.info("🔄 Invalidating OAuth key cache to reload from Couchbase");
        cachedRsaKey = null;
    }

    // FhirServerConfig was previously used for deriving issuer; logic now moved to authorizationServerSettings
    // Remove unused injection to avoid warning.

    /**
     * OAuth 2.0 Authorization Server Security Filter Chain
     * Uses Spring's standard configuration pattern
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
            throws Exception {
        // Apply Spring Authorization Server's default configuration.
        // This ensures ALL standard endpoints are correctly mapped, including:
        // - /oauth2/authorize
        // - /oauth2/token
        // - /oauth2/introspect
        // - /oauth2/revoke
        // - /oauth2/jwks
        // - /.well-known/oauth-authorization-server
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        // Enable OpenID Connect 1.0
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .oidc(Customizer.withDefaults());

        // Redirect to login page when not authenticated for HTML requests
        http.exceptionHandling((exceptions) -> exceptions
                .defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                )
        );

        // Accept access tokens for User Info and/or Client Registration
        http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Register OAuth 2.0 clients
     * For testing, we create a simple client with default SMART scopes
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(
        @Value("${admin.ui.client.secret:}") String adminUiClientSecretProp,
        @Value("${admin.ui.client.id:admin-ui}") String adminUiClientId,
        @Value("${admin.ui.scopes:system/*.*,user/*.*}") String adminUiScopesProp) {
    // Derive static admin-ui client secret:
    // Priority: explicit property -> env var ADMIN_UI_CLIENT_SECRET -> fallback test-secret (NOT for production).
    String envSecret = System.getenv("ADMIN_UI_CLIENT_SECRET");
    String rawAdminSecret = (adminUiClientSecretProp != null && !adminUiClientSecretProp.isBlank()) ? adminUiClientSecretProp
        : (envSecret != null && !envSecret.isBlank()) ? envSecret
        : "change-me-admin-ui-secret";
    if ("change-me-admin-ui-secret".equals(rawAdminSecret)) {
        logger.warn("⚠️ Using fallback admin-ui client secret. Set ADMIN_UI_CLIENT_SECRET or admin.ui.client.secret property for production.");
    }

    // Parse scopes (comma or space separated)
    String[] adminScopesArr = adminUiScopesProp.split("[ ,]+");

    // Static admin client for interactive logins (client_credentials only)
    RegisteredClient adminClient = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(adminUiClientId)
        .clientSecret(passwordEncoder().encode(rawAdminSecret))
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scopes(s -> {
            for (String sc : adminScopesArr) if (!sc.isBlank()) s.add(sc.trim());
        })
        .tokenSettings(TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofHours(
                Long.parseLong(System.getProperty("oauth.token.expiry.hours",
                    System.getenv().getOrDefault("OAUTH_TOKEN_EXPIRY_HOURS", "24"))))
            )
            .build())
        .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
        .build();

    // Existing test client for development / SMART flows
    RegisteredClient testClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("test-client")
                .clientSecret(passwordEncoder().encode("test-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                // Redirect URIs for testing - support both local dev and Docker deployment
                .redirectUri("http://localhost:8080/authorized")      // Local dev (backend port)
                .redirectUri("http://localhost/authorized")           // Docker (HAProxy port 80)
                .redirectUri("http://localhost:3000/callback")        // Local dev (frontend)
                .redirectUri("http://localhost/callback")             // Docker (frontend via HAProxy)
                .redirectUri("http://127.0.0.1:8080/authorized")      // Alternative localhost
                .postLogoutRedirectUri("http://localhost:8080/")      // Local dev
                .postLogoutRedirectUri("http://localhost/")           // Docker
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(SmartScopes.FHIRUSER)
                .scope(SmartScopes.ONLINE_ACCESS)
                .scope(SmartScopes.PATIENT_ALL_READ)
                .scope(SmartScopes.PATIENT_ALL_WRITE)
                .scope(SmartScopes.USER_ALL_READ)
                .scope(SmartScopes.USER_ALL_WRITE)
                .scope(SmartScopes.SYSTEM_ALL_READ)
                .scope(SmartScopes.SYSTEM_ALL_WRITE)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true) // Require user consent
                        .build())
                .tokenSettings(TokenSettings.builder()
                        // Access token expiry: configurable via environment variable
                        // Default: 24 hours for testing/development
                        // Production: 1 hour recommended
                        .accessTokenTimeToLive(Duration.ofHours(
                            Long.parseLong(System.getProperty("oauth.token.expiry.hours", 
                                          System.getenv().getOrDefault("OAUTH_TOKEN_EXPIRY_HOURS", "24")))
                        ))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(java.util.List.of(adminClient, testClient));
    }

    /**
     * User details for OAuth authentication
     * In production, this should use Couchbase fhir.Admin.users
     */
    @Bean
    public UserDetailsService userDetailsService() {
        // Test user for development
        UserDetails user = User.builder()
                .username("fhiruser")
                .password(passwordEncoder().encode("password"))
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Password encoder for client secrets and user passwords
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * JWT token customizer to add SMART scopes and custom claims
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (context.getTokenType().getValue().equals("access_token")) {
                // Add SMART-specific claims
                context.getClaims().claim("scope", context.getAuthorizedScopes());
                
                // Add fhirUser claim if scope includes fhirUser
                if (context.getAuthorizedScopes().contains(SmartScopes.FHIRUSER)) {
                    String username = context.getPrincipal().getName();
                    context.getClaims().claim("fhirUser", "Practitioner/" + username);
                }
                
                // Add patient context if patient scope is present
                // TODO: In production, retrieve actual patient ID from context
                if (context.getAuthorizedScopes().stream().anyMatch(s -> s.startsWith("patient/"))) {
                    context.getClaims().claim("patient", "example-patient-123");
                }
            }
        };
    }

    /**
     * JWT decoder for validating access tokens
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * JwtEncoder for issuing custom application tokens (e.g., admin login) signed with same JWK.
     */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * JWK Source for signing and validating JWTs
     * Uses lazy-loading pattern to ensure fhir.Admin.config collection exists
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        // Return a dynamic JWKSource that lazy-loads the key on first use
        return (jwkSelector, securityContext) -> {
            if (cachedRsaKey == null) {
                synchronized (this) {
                    if (cachedRsaKey == null) {
                        cachedRsaKey = loadOrCreateRsaJwkFromCouchbase();
                    }
                }
            }
            JWKSet jwkSet = new JWKSet(cachedRsaKey);
            return jwkSelector.select(jwkSet);
        };
    }

    /**
     * Generate RSA key pair for JWT signing
     */
    private static KeyPair generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }

    /**
     * Get the config collection from Couchbase
     * Returns null if collection doesn't exist yet (during initialization)
     * FAST-FAIL: Checks collection existence before attempting KV operations
     */
    private boolean isConfigCollectionAvailable() {
        Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
        if (cluster == null) {
            logger.debug("No active Couchbase connection for '{}'", DEFAULT_CONNECTION);
            return false;
        }
        boolean bucketOk = false, scopeOk = false, collectionOk = false;
        try {
            String bucketCheckSql = "SELECT RAW name FROM system:buckets WHERE `name` = $name LIMIT 1";
            var result = cluster.query(bucketCheckSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("name", BUCKET_NAME)));
            bucketOk = !result.rowsAs(String.class).isEmpty();
            if (!bucketOk) logger.debug("Bucket '{}' NOT found (system:buckets)", BUCKET_NAME);
        } catch (Exception e) {
            logger.debug("Bucket existence check error: {}", e.getMessage());
        }
        try {
            if (bucketOk) {
                String scopeSql = "SELECT RAW name FROM system:scopes WHERE `bucket` = $bucket AND `name` = $scope LIMIT 1";
                var result = cluster.query(scopeSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("bucket", BUCKET_NAME).put("scope", SCOPE_NAME)));
                scopeOk = !result.rowsAs(String.class).isEmpty();
                if (!scopeOk) logger.debug("Scope '{}.{}' NOT found (system:scopes)", BUCKET_NAME, SCOPE_NAME);
            }
        } catch (Exception e) {
            logger.debug("Scope existence check error: {}", e.getMessage());
        }
        try {
            if (bucketOk && scopeOk) {
                String collSql = "SELECT RAW name FROM system:keyspaces WHERE `bucket` = $bucket AND `scope` = $scope AND `name` = $name LIMIT 1";
                var result = cluster.query(collSql, com.couchbase.client.java.query.QueryOptions.queryOptions().parameters(JsonObject.create().put("bucket", BUCKET_NAME).put("scope", SCOPE_NAME).put("name", COLLECTION_NAME)));
                collectionOk = !result.rowsAs(String.class).isEmpty();
                if (!collectionOk) logger.debug("Collection '{}.{}.{}' NOT found (system:keyspaces)", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME);
            }
        } catch (Exception e) {
            logger.debug("Collection existence check error: {}", e.getMessage());
        }
        if (!(bucketOk && scopeOk && collectionOk)) {
            logger.info("OAuth JWK collection unavailable (bucketOk={} scopeOk={} collectionOk={})", bucketOk, scopeOk, collectionOk);
        }
        return bucketOk && scopeOk && collectionOk;
    }

    /**
     * Load existing RSA JWK from Couchbase - ONLY loads, never creates
     * Key must be created during initialization (Step 11)
     * Stored in: fhir.Admin.config collection with document ID "oauth-signing-key"
     */
    private RSAKey loadOrCreateRsaJwkFromCouchbase() {
        if (!isConfigCollectionAvailable()) {
            throw new IllegalStateException("Cannot load OAuth signing key: fhir.Admin.config collection does not exist. Please initialize the FHIR bucket first.");
        }
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection");
            }
             // N1QL fetch (avoids KV timeout). Use VALUE to get raw doc object.
             // IMPORTANT: Previous query used SELECT VALUE d without alias; resulted in null row. Corrected to use RAW with alias.
             String docSql = String.format("SELECT RAW d FROM `%s`.`%s`.`%s` AS d USE KEYS '%s'", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME, JWK_DOCUMENT_ID);
             long start = System.nanoTime();
             logger.debug("🔍 [JWK-LOAD] Executing signing key query: {}", docSql);
             var queryResult = cluster.query(docSql);
             var docs = queryResult.rowsAs(JsonObject.class);
             long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
             logger.debug("🔍 [JWK-LOAD] Query completed in {} ms; rowCount={}", elapsedMs, docs.size());

            if (docs.isEmpty()) {
                logger.warn("❌ OAuth signing key document not found in fhir.Admin.config - was Step 11 skipped during initialization?");
                // For deeper diagnostics, log any raw rows as String (should be empty too)
                var rawRows = queryResult.rowsAs(String.class);
                logger.debug("[JWK-LOAD] Raw string rows size={} contents={}", rawRows.size(), rawRows);
                throw new IllegalStateException("OAuth signing key not found in fhir.Admin.config. Please initialize the FHIR bucket first.");
            }
            JsonObject doc = docs.get(0);
            if (doc == null) {
                // Fallback: attempt alternate projection without alias to see if document loads
                String altSql = String.format("SELECT RAW `%s`.`%s`.`%s` USE KEYS '%s'", BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME, JWK_DOCUMENT_ID);
                logger.warn("[JWK-LOAD] Null row returned; attempting alternate query: {}", altSql);
                var altResult = cluster.query(altSql);
                var altDocs = altResult.rowsAs(JsonObject.class);
                if (!altDocs.isEmpty() && altDocs.get(0) != null) {
                    doc = altDocs.get(0);
                    logger.info("[JWK-LOAD] Alternate query succeeded; continuing with loaded document.");
                } else {
                    var rawRows = queryResult.rowsAs(String.class);
                    logger.warn("❌ First row still null after alternate query. originalRawRowsSize={} originalRawRows={} altRowsSize={} altRows={}", rawRows.size(), rawRows, altDocs.size(), altResult.rowsAs(String.class));
                    throw new IllegalStateException("Signing key query returned null row (both primary and alternate forms)");
                }
            }
             // Log truncated doc for diagnostics (avoid dumping private key material fully)
             logger.debug("🔍 [JWK-LOAD] Retrieved doc keys={}", doc.getNames());
             
             // Try new format first (jwkSetString) - stores JWK as raw string to preserve private key
             String jwkSetJson = doc.getString("jwkSetString");
             if (jwkSetJson == null) {
                 // Fallback to old format (jwkSet as JsonObject) for backwards compatibility
                 logger.warn("⚠️ [JWK-LOAD] No 'jwkSetString' field, trying legacy 'jwkSet' field...");
                 JsonObject jwkSetObj = doc.getObject("jwkSet");
                 if (jwkSetObj == null) {
                     logger.error("❌ [JWK-LOAD] Document has neither 'jwkSetString' nor 'jwkSet' field. Document structure: {}", doc.getNames());
                     throw new IllegalStateException("JWK document exists but has no jwkSet");
                 }
                 jwkSetJson = jwkSetObj.toString();
                 logger.warn("⚠️ [JWK-LOAD] Using legacy 'jwkSet' field - private key may be missing!");
             }
             logger.debug("🔍 [JWK-LOAD] jwkSet found, parsing JWKSet JSON...");
             logger.debug("🔍 [JWK-LOAD] jwkSet JSON length: {} chars", jwkSetJson.length());
             
             JWKSet set = JWKSet.parse(jwkSetJson);
             logger.debug("🔍 [JWK-LOAD] Parsed JWKSet with {} keys", set.getKeys().size());
             
             RSAKey loaded = (RSAKey) set.getKeys().get(0);
             logger.debug("🔍 [JWK-LOAD] First key: kty={}, kid={}, hasPrivateKey={}", 
                 loaded.getKeyType(), loaded.getKeyID(), loaded.isPrivate());
             
             if (!loaded.isPrivate()) {
                 logger.error("❌ [JWK-LOAD] RSA key lacks private key! This means the JWKSet was exported without private parts.");
                 logger.error("❌ [JWK-LOAD] Key details: algorithm={}, keyUse={}, keyOperations={}", 
                     loaded.getAlgorithm(), loaded.getKeyUse(), loaded.getKeyOperations());
                 throw new IllegalStateException("JWK in Couchbase lacks private key - was it exported without private parts?");
             }
             logger.info("🔐 Loaded existing RSA signing key from fhir.Admin.config (kid: {})", loaded.getKeyID());
             return loaded;
        } catch (Exception e) {
            logger.error("❌ Failed to load OAuth signing key via N1QL: {}", e.getMessage());
            throw new IllegalStateException("Failed to load OAuth signing key: " + e.getMessage(), e);
        }
    }

    /**
     * Authorization Server settings (endpoint URLs)
     * Uses base URL from config.yaml (app.baseUrl)
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${app.baseUrl:http://localhost:8080/fhir}") String configBaseUrl) {
        // Extract issuer by removing the /fhir path while preserving the full host:port
        // Example: http://localhost:8080/fhir -> http://localhost:8080
        String issuer = configBaseUrl;
        if (issuer.endsWith("/fhir")) {
            issuer = issuer.substring(0, issuer.length() - 5); // Remove last 5 chars: "/fhir"
        }
        
        logger.info("🔐 OAuth Authorization Server issuer: {}", issuer);
        
        return AuthorizationServerSettings.builder()
                .issuer(issuer) // Uses base URL from config.yaml
                .authorizationEndpoint("/oauth2/authorize")
                .tokenEndpoint("/oauth2/token")
                .tokenIntrospectionEndpoint("/oauth2/introspect")
                .tokenRevocationEndpoint("/oauth2/revoke")
                .jwkSetEndpoint("/oauth2/jwks")
                .oidcUserInfoEndpoint("/oauth2/userinfo")
                .build();
    }
}

