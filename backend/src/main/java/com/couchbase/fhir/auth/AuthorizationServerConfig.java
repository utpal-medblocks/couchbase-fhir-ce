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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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
    
    @Autowired
    private com.couchbase.fhir.auth.service.PatientContextService patientContextService;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    // The RSA signing key - generated on startup, persisted during initialization
    private volatile RSAKey signingKey = null;
    
    /**
     * Initialize the OAuth signing key after Couchbase connection is established
     * Called explicitly by ConfigurationStartupService after connection succeeds
     * Strategy:
     * 1. Try to load from fhir.Admin.config (if bucket initialized)
     * 2. If not found, generate new key in-memory
     * 3. During initialization (Step 11), persist the key to Couchbase
     * 4. On next restart, load the persisted key
     */
    public void initializeSigningKey() {
        logger.info("🔐 Initializing OAuth signing key...");
        
        // Try to load existing key from bucket (if initialized)
        if (isConfigCollectionAvailable()) {
            try {
                RSAKey loadedKey = loadKeyFromCouchbase();
                if (loadedKey != null) {
                    this.signingKey = loadedKey;
                    logger.info("✅ Loaded existing OAuth signing key from fhir.Admin.config (kid: {})", loadedKey.getKeyID());
                    return;
                }
            } catch (Exception e) {
                logger.warn("⚠️ Failed to load key from Couchbase: {} - will generate new key", e.getMessage());
            }
        }
        
        // No persisted key found - generate new key
        try {
            RSAKey newKey = generateRsaKey();
            this.signingKey = newKey;
            logger.info("✅ Generated new OAuth signing key (kid: {})", newKey.getKeyID());
            logger.warn("⚠️ Key is in-memory only - will NOT survive restart until FHIR bucket is initialized");
        } catch (Exception e) {
            logger.error("❌ Failed to generate OAuth signing key: {}", e.getMessage(), e);
            throw new IllegalStateException("Cannot start without OAuth signing key", e);
        }
    }
    
    /**
     * Get the current signing key (for persistence during initialization)
     */
    public RSAKey getCurrentKey() {
        return signingKey;
    }
    
    /**
     * Called after a new key is persisted to Couchbase during initialization
     * This is a no-op now since we already have the key in memory
     */
    public void invalidateKeyCache() {
        // No-op: key is already in memory and doesn't need reloading
        logger.debug("🔄 Key cache invalidation called (no-op - key already in memory)");
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

        // Enable OpenID Connect 1.0 (get the configurer from http after applyDefaultSecurity)
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
        @Value("${admin.ui.scopes:system/*.*,user/*.*}") String adminUiScopesProp,
        @Value("${app.baseUrl}") String configBaseUrl,
        @Value("${frontend.dev.port:3000}") int frontendDevPort) {
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
        .clientSecret(passwordEncoder.encode(rawAdminSecret))
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

    // Derive issuer (strip trailing /fhir if present) and compute dynamic redirect bases
    String issuer = configBaseUrl.endsWith("/fhir") ? configBaseUrl.substring(0, configBaseUrl.length() - 5) : configBaseUrl;
    URI issuerUri = URI.create(issuer);
    String scheme = issuerUri.getScheme() == null ? "http" : issuerUri.getScheme();
    String host = issuerUri.getHost() == null ? "localhost" : issuerUri.getHost();
    int port = issuerUri.getPort();
    String hostOnlyBase = scheme + "://" + host;                           // e.g. http://localhost
    String issuerWithPort = (port == -1 ? hostOnlyBase : hostOnlyBase + ":" + port); // e.g. http://localhost
    String altLoopbackBase = scheme + "://127.0.0.1" + (port == -1 ? "" : ":" + port); // 127.0.0.1 variant
    String frontendDevBase = scheme + "://" + host + ":" + frontendDevPort;            // e.g. http://localhost:3000

    logger.debug("OAuth redirect bases resolved: issuerWithPort={}, hostOnlyBase={}, altLoopbackBase={}, frontendDevBase={}",
        issuerWithPort, hostOnlyBase, altLoopbackBase, frontendDevBase);

    // Existing test client for development / SMART flows (dynamic redirect URIs)
    RegisteredClient testClient = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("test-client")
        .clientSecret(passwordEncoder.encode("test-secret"))
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        // Redirect URIs constructed from config base URL & dev frontend port
        .redirectUri(issuerWithPort + "/authorized")        // Primary backend URL
        .redirectUri(hostOnlyBase + "/authorized")          // Host-only (HAProxy 80)
        .redirectUri(frontendDevBase + "/callback")         // Frontend dev port
        .redirectUri(hostOnlyBase + "/callback")            // Host-only callback
        .redirectUri(altLoopbackBase + "/authorized")       // 127.0.0.1 alternative
        .postLogoutRedirectUri(issuerWithPort + "/")         // Post-logout backend
        .postLogoutRedirectUri(hostOnlyBase + "/")           // Post-logout host-only
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
     * 
     * IMPORTANT: This bean is intentionally NOT defined here.
     * Spring will auto-wire the CouchbaseUserDetailsService (annotated with @Service("couchbaseUserDetailsService"))
     * which loads users from fhir.Admin.users collection.
     * 
     * Users must exist in Couchbase with:
     * - status: "active"
     * - authMethod: "local"
     * - passwordHash: BCrypt hashed password
     * - role: "admin", "developer", or "smart_user"
     * 
     * For backward compatibility during development, you can create a test user via:
     * POST /api/admin/users with:
     * {
     *   "id": "fhiruser@example.com",
     *   "username": "FHIR User",
     *   "email": "fhiruser@example.com",
     *   "password": "password",
     *   "role": "smart_user",
     *   "authMethod": "local"
     * }
     */
    // UserDetailsService bean now provided by CouchbaseUserDetailsService
    // @Bean public UserDetailsService userDetailsService() { ... }

    // PasswordEncoder bean now provided by PasswordEncoderConfig (separate config to avoid circular dependencies)
    // @Bean public PasswordEncoder passwordEncoder() { ... }

    /**
     * JWT token customizer to add SMART scopes and custom claims
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (context.getTokenType().getValue().equals("access_token")) {
                // Add token type for explicit identification (hardening)
                context.getClaims().claim("token_type", "oauth");
                
                // Add SMART-specific claims
                // Convert scopes to space-separated string (OAuth 2.0 standard)
                String scopeString = String.join(" ", context.getAuthorizedScopes());
                context.getClaims().claim("scope", scopeString);
                
                // Add fhirUser claim if scope includes fhirUser
                if (context.getAuthorizedScopes().contains(SmartScopes.FHIRUSER)) {
                    String username = context.getPrincipal().getName();
                    context.getClaims().claim("fhirUser", "Practitioner/" + username);
                }
                
                // Add patient context if patient scope is present
                if (context.getAuthorizedScopes().stream().anyMatch(s -> s.startsWith("patient/"))) {
                    String username = context.getPrincipal().getName();
                    String authorizationId = context.getAuthorization() != null ? context.getAuthorization().getId() : null;
                    
                    // Resolve patient context using PatientContextService
                    String patientId = patientContextService.resolvePatientContext(username, authorizationId);
                    
                    if (patientId != null) {
                        logger.debug("✅ Adding patient claim: {}", patientId);
                        context.getClaims().claim("patient", patientId);
                    } else {
                        logger.warn("⚠️ No patient context found for user: {}", username);
                        // Don't add patient claim if none found
                        // SMART apps should handle missing patient context gracefully
                    }
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
     * JWT Authentication Converter for extracting authorities from scope claim
     * Uses our custom converter that safely handles both String and Collection scope claims
     */
    @Bean
    public org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter jwtAuthenticationConverter() {
        logger.info("🔧 Configuring JwtAuthenticationConverter with CustomJwtGrantedAuthoritiesConverter");
        
        // Use our custom converter that handles both String and Collection types
        CustomJwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new CustomJwtGrantedAuthoritiesConverter();
        
        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter jwtAuthenticationConverter = 
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        
        logger.info("✅ JwtAuthenticationConverter configured with custom scope handler");
        return jwtAuthenticationConverter;
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
     * Uses the pre-initialized signing key from @PostConstruct
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        return (jwkSelector, securityContext) -> {
            if (signingKey == null) {
                throw new IllegalStateException("OAuth signing key not initialized");
            }
            JWKSet jwkSet = new JWKSet(signingKey);
            return jwkSelector.select(jwkSet);
        };
    }
    
    /**
     * Generate a new RSA key pair for JWT signing
     */
    private RSAKey generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
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
     * Load existing RSA JWK from Couchbase
     * Returns null if key doesn't exist (will generate new one)
     * Stored in: fhir.Admin.config collection with document ID "oauth-signing-key"
     */
    private RSAKey loadKeyFromCouchbase() {
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                logger.debug("No active Couchbase connection");
                return null;
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
                logger.debug("OAuth signing key document not found in fhir.Admin.config (bucket not initialized yet)");
                return null;
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
             return loaded;
        } catch (Exception e) {
            logger.warn("Failed to load OAuth signing key from Couchbase: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Authorization Server settings (endpoint URLs)
     * Uses base URL from config.yaml (app.baseUrl)
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
        @Value("${app.baseUrl}") String configBaseUrl) {
        // Extract issuer by removing the /fhir path while preserving the full host:port
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

