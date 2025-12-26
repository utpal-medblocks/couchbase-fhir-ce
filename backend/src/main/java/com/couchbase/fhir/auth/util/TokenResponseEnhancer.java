package com.couchbase.fhir.auth.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Utility to enhance token response JSON by extracting claims from the access_token JWT
 * and adding top-level fields such as `patient` and `fhirUser` when present.
 */
public class TokenResponseEnhancer {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String enhance(String originalJson) {
        if (originalJson == null || originalJson.isBlank()) return originalJson;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenResponse = mapper.readValue(originalJson, Map.class);

            // If this is an error response, don't modify
            if (tokenResponse.containsKey("error")) {
                return originalJson;
            }

            // If access_token exists, decode its payload and look for claims
            Object accessTokenObj = tokenResponse.get("access_token");
            if (accessTokenObj instanceof String) {
                String accessToken = (String) accessTokenObj;
                if (accessToken.contains(".")) {
                    String[] parts = accessToken.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> claims = mapper.readValue(payloadJson, Map.class);

                        // Only add patient/fhirUser if not already present at top-level
                        if (!tokenResponse.containsKey("patient") && claims.containsKey("patient")) {
                            tokenResponse.put("patient", claims.get("patient"));
                        }
                        if (!tokenResponse.containsKey("fhirUser") && claims.containsKey("fhirUser")) {
                            tokenResponse.put("fhirUser", claims.get("fhirUser"));
                        }
                    }
                }
            }

            // Return modified JSON
            return mapper.writeValueAsString(tokenResponse);
        } catch (Exception e) {
            // On any error, return original unchanged
            return originalJson;
        }
    }
}
