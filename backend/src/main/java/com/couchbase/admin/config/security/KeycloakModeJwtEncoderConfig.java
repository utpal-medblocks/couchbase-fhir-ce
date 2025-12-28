package com.couchbase.admin.config.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * When Keycloak is used as the authorization server we don't provide an embedded
 * JwtEncoder for issuing tokens. Some beans (e.g. TokenService) may still expect
 * a JwtEncoder bean to exist; provide a clear stub here so the application
 * context can start and any accidental use results in an explicit error.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "true")
public class KeycloakModeJwtEncoderConfig {

    @Bean
    public JwtEncoder jwtEncoder() {
        return new JwtEncoder() {
            @Override
            public Jwt encode(JwtEncoderParameters parameters) throws JwtException {
                throw new UnsupportedOperationException(
                        "JwtEncoder is not available when Keycloak is used as the authorization server. "
                                + "Tokens should be issued by Keycloak in this mode.");
            }
        };
    }
}
