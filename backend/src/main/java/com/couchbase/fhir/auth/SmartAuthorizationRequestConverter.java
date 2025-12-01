package com.couchbase.fhir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom OAuth2 Authorization Request Converter for SMART on FHIR
 * 
 * Captures additional SMART-specific parameters from the authorization request:
 * - patient_id: Explicit patient context for the session
 * - launch: EHR launch context (for future EHR launch support)
 * 
 * These parameters are stored in the OAuth2Authorization and can be
 * retrieved during token generation to add appropriate claims.
 */
public class SmartAuthorizationRequestConverter {
    
    private static final AntPathRequestMatcher AUTHORIZATION_REQUEST_MATCHER = 
        new AntPathRequestMatcher("/oauth2/authorize");
    
    /**
     * Extract additional SMART parameters from the authorization request
     * 
     * @param request HTTP servlet request
     * @return Map of additional parameters to be stored in authorization context
     */
    public static Map<String, Object> extractAdditionalParameters(HttpServletRequest request) {
        if (!AUTHORIZATION_REQUEST_MATCHER.matches(request)) {
            return null;
        }
        
        Map<String, Object> additionalParameters = new HashMap<>();
        
        // Extract patient_id if provided
        String patientId = request.getParameter("patient_id");
        if (StringUtils.hasText(patientId)) {
            additionalParameters.put("patient_id", patientId);
        }
        
        // Extract launch token if provided (for future EHR launch support)
        String launch = request.getParameter("launch");
        if (StringUtils.hasText(launch)) {
            additionalParameters.put("launch", launch);
        }
        
        // Extract aud (audience) parameter (SMART requirement)
        String aud = request.getParameter("aud");
        if (StringUtils.hasText(aud)) {
            additionalParameters.put("aud", aud);
        }
        
        return additionalParameters.isEmpty() ? null : additionalParameters;
    }
    
    /**
     * Convert OAuth2AuthorizationRequest with additional SMART parameters
     * 
     * This is used by the authorization endpoint to capture SMART-specific params
     * before redirecting to login/consent.
     */
    public static OAuth2AuthorizationRequest.Builder withAdditionalParameters(
            OAuth2AuthorizationRequest authorizationRequest,
            Map<String, Object> additionalParameters) {
        
        if (additionalParameters == null || additionalParameters.isEmpty()) {
            return OAuth2AuthorizationRequest.from(authorizationRequest);
        }
        
        // Create a new builder with the original request's properties
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.from(authorizationRequest);
        
        // Add additional parameters to the request
        Map<String, Object> allAdditionalParams = new HashMap<>(authorizationRequest.getAdditionalParameters());
        allAdditionalParams.putAll(additionalParameters);
        builder.additionalParameters(allAdditionalParams);
        
        return builder;
    }
}

