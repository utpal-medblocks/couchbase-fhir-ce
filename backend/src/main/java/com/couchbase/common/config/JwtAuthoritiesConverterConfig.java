package com.couchbase.common.config;

import com.couchbase.fhir.auth.CustomJwtGrantedAuthoritiesConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Configuration for JWT Authentication Converter.
 * Provides a single bean that works for both embedded and Keycloak modes.
 * Uses CustomJwtGrantedAuthoritiesConverter to safely handle both String and Collection scope claims.
 */
@Configuration
public class JwtAuthoritiesConverterConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthoritiesConverterConfig.class);

    /**
     * Create the JwtAuthenticationConverter bean with custom authorities converter.
     * This bean is used by SecurityConfig for all JWT-secured endpoints.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        logger.info("🔧 Configuring JwtAuthenticationConverter with CustomJwtGrantedAuthoritiesConverter");
        
        // Use our custom converter that handles both String and Collection types
        // and extracts both SMART scopes and Keycloak roles
        CustomJwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = 
            new CustomJwtGrantedAuthoritiesConverter();
        
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        
        logger.info("✅ JwtAuthenticationConverter configured with custom scope handler");
        return converter;
    }
}

