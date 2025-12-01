package com.couchbase.fhir.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * Intercepts OAuth token endpoint responses to add SMART on FHIR specific claims
 * to the top-level response body (not just JWT claims).
 * 
 * This ensures Inferno and other SMART apps receive the patient context they expect.
 */
@ControllerAdvice
public class SmartTokenResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger logger = LoggerFactory.getLogger(SmartTokenResponseAdvice.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Only intercept responses that might be from token endpoint
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        
        // Only process token endpoint responses
        String path = request.getURI().getPath();
        if (!path.endsWith("/oauth2/token")) {
            return body;
        }

        logger.info("════════════════════════════════════════════════════════════");
        logger.info("🎫 [SMART-TOKEN-ADVICE] Intercepting token endpoint response");
        logger.info("════════════════════════════════════════════════════════════");
        logger.info("📋 Request Path: {}", path);
        logger.info("📋 Response Body Type: {}", body != null ? body.getClass().getName() : "null");

        if (body instanceof Map) {
            Map<String, Object> tokenResponse = (Map<String, Object>) body;
            logger.info("📦 [TOKEN-RESPONSE] Original keys: {}", tokenResponse.keySet());
            
            // Extract patient claim from access_token JWT
            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken != null && accessToken.contains(".")) {
                try {
                    String[] parts = accessToken.split("\\.");
                    if (parts.length >= 2) {
                        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                        Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                        
                        logger.info("🔐 [ACCESS-TOKEN-CLAIMS] JWT claims: {}", claims.keySet());
                        
                        // Add patient claim to top-level response if it exists in JWT
                        Object patientClaim = claims.get("patient");
                        if (patientClaim != null) {
                            tokenResponse.put("patient", patientClaim);
                            logger.info("✅ [PATIENT-INJECT] Added 'patient' to top-level response: {}", patientClaim);
                        } else {
                            logger.warn("⚠️ [PATIENT-INJECT] No 'patient' claim found in JWT. Claims: {}", claims.keySet());
                        }
                        
                        // Log fhirUser if present
                        Object fhirUserClaim = claims.get("fhirUser");
                        if (fhirUserClaim != null) {
                            logger.info("✅ [FHIR-USER] Found 'fhirUser' in JWT: {}", fhirUserClaim);
                        }
                    }
                } catch (Exception e) {
                    logger.error("❌ [TOKEN-DECODE] Failed to decode access_token: {}", e.getMessage(), e);
                }
            }
            
            logger.info("📦 [TOKEN-RESPONSE] Final keys: {}", tokenResponse.keySet());
            logger.info("📦 [TOKEN-RESPONSE] Final response: {}", tokenResponse);
            logger.info("════════════════════════════════════════════════════════════");
            
            return tokenResponse;
        }
        
        logger.warn("⚠️ [SMART-TOKEN-ADVICE] Response body is not a Map, cannot inject patient claim");
        logger.info("════════════════════════════════════════════════════════════");
        return body;
    }
}

