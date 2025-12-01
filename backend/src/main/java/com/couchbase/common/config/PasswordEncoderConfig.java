package com.couchbase.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password Encoder Configuration
 * 
 * Separate configuration to avoid circular dependencies.
 * This bean is used by multiple components:
 * - UserService (for password hashing)
 * - AuthorizationServerConfig (for client secrets)
 * - AuthController (for password verification)
 */
@Configuration
public class PasswordEncoderConfig {
    
    /**
     * Password encoder for client secrets and user passwords
     * Uses Spring Security's DelegatingPasswordEncoder which supports:
     * - {bcrypt} (default)
     * - {pbkdf2}
     * - {scrypt}
     * - {argon2}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

