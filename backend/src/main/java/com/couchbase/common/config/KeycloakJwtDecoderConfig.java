package com.couchbase.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "true")
public class KeycloakJwtDecoderConfig {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakJwtDecoderConfig.class);

    @Value("${KEYCLOAK_JWKS_URI:}")
    private String jwksUri;

    @Bean
    public JwtDecoder jwtDecoder() {
        if (jwksUri == null || jwksUri.isBlank()) {
            logger.error("KEYCLOAK_JWKS_URI is not set but Keycloak integration is enabled. Set KEYCLOAK_JWKS_URI to the Keycloak JWKS endpoint.");
            throw new IllegalStateException("Missing KEYCLOAK_JWKS_URI environment variable");
        }
        logger.info("Configuring JwtDecoder to use Keycloak JWKS URI: {}", jwksUri);
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
