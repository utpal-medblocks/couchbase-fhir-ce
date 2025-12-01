package com.couchbase.fhir.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Filter to log OAuth token endpoint responses for debugging.
 */
@Component
@Order(1)
public class TokenResponseLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TokenResponseLoggingFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Only log token endpoint responses
        if (!httpRequest.getRequestURI().equals("/oauth2/token")) {
            chain.doFilter(request, response);
            return;
        }

        logger.info("🎫 [TOKEN-ENDPOINT] Request received: {} {}", httpRequest.getMethod(), httpRequest.getRequestURI());
        
        // Wrap response to capture the content
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);

        try {
            chain.doFilter(request, responseWrapper);
        } finally {
            logResponse(responseWrapper);
            responseWrapper.copyBodyToResponse();
        }
    }

    @SuppressWarnings("unchecked")
    private void logResponse(ContentCachingResponseWrapper responseWrapper) {
        try {
            byte[] content = responseWrapper.getContentAsByteArray();
            if (content.length > 0) {
                String responseBody = new String(content, StandardCharsets.UTF_8);
                
                logger.info("════════════════════════════════════════════════════════════");
                logger.info("🎫 [TOKEN-ENDPOINT-RESPONSE] Complete OAuth Token Response:");
                logger.info("════════════════════════════════════════════════════════════");
                logger.info("📊 Status: {}", responseWrapper.getStatus());
                logger.info("📋 Content-Type: {}", responseWrapper.getContentType());
                
                // Try to parse as JSON and log prettily
                try {
                    Map<String, Object> json = (Map<String, Object>) objectMapper.readValue(responseBody, Map.class);
                    
                    logger.info("📦 Response Body (parsed):");
                    json.forEach((key, value) -> {
                        if (key.equals("access_token") || key.equals("refresh_token") || key.equals("id_token")) {
                            // Truncate tokens for readability
                            String tokenValue = value.toString();
                            logger.info("  • {}: {}... (length: {})", key, 
                                tokenValue.substring(0, Math.min(50, tokenValue.length())), 
                                tokenValue.length());
                            
                            // Decode and log JWT claims
                            if (tokenValue.contains(".")) {
                                try {
                                    String[] parts = tokenValue.split("\\.");
                                    if (parts.length >= 2) {
                                        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                                        Map<String, Object> claims = (Map<String, Object>) objectMapper.readValue(payload, Map.class);
                                        logger.info("    🔐 [{}] Claims:", key.toUpperCase().replace("_", " "));
                                        claims.forEach((claimKey, claimValue) -> {
                                            logger.info("      - {}: {}", claimKey, claimValue);
                                        });
                                    }
                                } catch (Exception e) {
                                    logger.debug("Could not decode JWT: {}", e.getMessage());
                                }
                            }
                        } else {
                            logger.info("  • {}: {}", key, value);
                        }
                    });
                    
                    // Check for patient claim in top-level response
                    if (json.containsKey("patient")) {
                        logger.info("✅ [PATIENT-CLAIM] Found 'patient' in top-level response: {}", json.get("patient"));
                    } else {
                        logger.warn("⚠️ [PATIENT-CLAIM] 'patient' NOT found in top-level response!");
                        logger.warn("⚠️ Available keys: {}", json.keySet());
                    }
                    
                } catch (Exception e) {
                    logger.info("📦 Response Body (raw): {}", responseBody);
                }
                
                logger.info("════════════════════════════════════════════════════════════");
            }
        } catch (Exception e) {
            logger.error("❌ Error logging token response: {}", e.getMessage(), e);
        }
    }
}

