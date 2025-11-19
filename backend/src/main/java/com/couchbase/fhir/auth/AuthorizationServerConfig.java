package com.couchbase.fhir.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

    @Autowired
    private com.couchbase.common.config.FhirServerConfig fhirServerConfig;

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
    public RegisteredClientRepository registeredClientRepository() {
        // Test client for development
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

        return new InMemoryRegisteredClientRepository(testClient);
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
        return new BCryptPasswordEncoder();
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
     * JWK Source for signing and validating JWTs
     * In production, use persistent key storage
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    /**
     * Generate RSA key pair for JWT signing
     * In production, load from secure key storage
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

