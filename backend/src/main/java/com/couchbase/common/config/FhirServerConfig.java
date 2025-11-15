package com.couchbase.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for FHIR Server base URL
 * Reads from system property set by ConfigurationStartupService
 */
@Component
public class FhirServerConfig {
    
    public String getBaseUrl() {
        // Read from system property (set by ConfigurationStartupService from config.yaml)
        String baseUrl = System.getProperty("app.baseUrl");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return "http://localhost/fhir";
        }
        return baseUrl;
    }
    
    /**
     * Get the normalized base URL, ensuring it ends with /fhir
     */
    public String getNormalizedBaseUrl() {
        String baseUrl = getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return "http://localhost/fhir";
        }
        
        String normalized = baseUrl.trim();
        
        // Ensure it ends with /fhir
        if (!normalized.endsWith("/fhir")) {
            // If it ends with /, add fhir
            if (normalized.endsWith("/")) {
                normalized = normalized + "fhir";
            } else {
                normalized = normalized + "/fhir";
            }
        }
        
        return normalized;
    }
}

