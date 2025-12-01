package com.couchbase.fhir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;

/**
 * Logs the complete OAuth token response for debugging.
 */
@Component
public class SmartTokenResponseEnhancer {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartTokenResponseEnhancer.class);
    
    public void logTokenResponse(OAuth2AccessTokenResponse tokenResponse) {
        try {
            logger.info("════════════════════════════════════════════════════════════");
            logger.info("🎫 [TOKEN-RESPONSE] Complete OAuth Token Response to Client:");
            logger.info("════════════════════════════════════════════════════════════");
            logger.info("📋 access_token: {} (expires in {} seconds)", 
                tokenResponse.getAccessToken().getTokenValue().substring(0, Math.min(50, tokenResponse.getAccessToken().getTokenValue().length())) + "...",
                tokenResponse.getAccessToken().getExpiresAt() != null ? 
                    java.time.Duration.between(java.time.Instant.now(), tokenResponse.getAccessToken().getExpiresAt()).getSeconds() : "N/A"
            );
            logger.info("📋 token_type: {}", tokenResponse.getAccessToken().getTokenType().getValue());
            logger.info("📋 scope: {}", tokenResponse.getAccessToken().getScopes());
            
            if (tokenResponse.getRefreshToken() != null) {
                logger.info("📋 refresh_token: {}...", 
                    tokenResponse.getRefreshToken().getTokenValue().substring(0, Math.min(20, tokenResponse.getRefreshToken().getTokenValue().length())));
            } else {
                logger.info("📋 refresh_token: (none)");
            }
            
            logger.info("📋 Additional parameters: {}", tokenResponse.getAdditionalParameters());
            
            // Decode and log access token claims
            if (tokenResponse.getAccessToken().getTokenValue().contains(".")) {
                String[] parts = tokenResponse.getAccessToken().getTokenValue().split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                    logger.info("🔐 [ACCESS-TOKEN-CLAIMS] {}", payload);
                }
            }
            
            logger.info("════════════════════════════════════════════════════════════");
            
        } catch (Exception e) {
            logger.error("❌ Error logging token response: {}", e.getMessage());
        }
    }
}

