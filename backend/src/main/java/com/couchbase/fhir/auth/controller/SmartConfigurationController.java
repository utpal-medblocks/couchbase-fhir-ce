package com.couchbase.fhir.auth.controller;

import com.couchbase.fhir.auth.SmartScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * SMART on FHIR Configuration Endpoint
 * 
 * Implements: http://www.hl7.org/fhir/smart-app-launch/conformance.html
 * 
 * Returns OAuth 2.0 endpoints and SMART capabilities for discovery.
 * SMART apps query this endpoint to discover:
 * - Authorization and token endpoints
 * - Supported scopes
 * - SMART capabilities
 * - Supported grant types
 */
@RestController
public class SmartConfigurationController {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartConfigurationController.class);
    
    /**
     * SMART Configuration Discovery Endpoint
     * GET /.well-known/smart-configuration (root)
     * GET /fhir/.well-known/smart-configuration (FHIR base)
     * 
     * Returns JSON describing the SMART server's OAuth 2.0 endpoints and capabilities.
     * This endpoint MUST be publicly accessible (no authentication required).
     */
    @GetMapping(value = {"/.well-known/smart-configuration", "/fhir/.well-known/smart-configuration"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getSmartConfiguration() {
        logger.debug("🔍 SMART configuration requested");
        
        // Read baseUrl from system property (set by ConfigurationStartupService from config.yaml)
        // This ensures we get the value from config.yaml, not application.yml default
        String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8080/fhir");
        
        // Extract issuer (remove /fhir suffix if present)
        String issuer = baseUrl;
        if (issuer.endsWith("/fhir")) {
            issuer = issuer.substring(0, issuer.length() - 5);
        }
        
        // Build SMART configuration response
        Map<String, Object> config = new LinkedHashMap<>();
        
        // OAuth 2.0 endpoints
        config.put("issuer", issuer);
        config.put("authorization_endpoint", issuer + "/oauth2/authorize");
        config.put("token_endpoint", issuer + "/oauth2/token");
        config.put("token_endpoint_auth_methods_supported", Arrays.asList(
            "client_secret_basic",
            "client_secret_post"
        ));
        config.put("registration_endpoint", issuer + "/oauth2/register"); // Future: Dynamic Client Registration
        config.put("introspection_endpoint", issuer + "/oauth2/introspect");
        config.put("revocation_endpoint", issuer + "/oauth2/revoke");
        config.put("jwks_uri", issuer + "/oauth2/jwks");
        
        // Supported scopes (SMART on FHIR)
        config.put("scopes_supported", Arrays.asList(
            // OpenID Connect
            "openid",
            "profile",
            "fhirUser",
            
            // Launch contexts
            "launch",
            "launch/patient",
            "online_access",
            "offline_access",
            
            // Patient scopes
            SmartScopes.PATIENT_ALL_READ,
            SmartScopes.PATIENT_ALL_WRITE,
            SmartScopes.PATIENT_ALL,
            "patient/Patient.read",
            "patient/Patient.write",
            "patient/Observation.read",
            "patient/Observation.write",
            "patient/Condition.read",
            "patient/Condition.write",
            "patient/MedicationRequest.read",
            "patient/MedicationRequest.write",
            "patient/AllergyIntolerance.read",
            "patient/Encounter.read",
            "patient/Procedure.read",
            "patient/DiagnosticReport.read",
            "patient/Immunization.read",
            "patient/DocumentReference.read",
            
            // User scopes
            SmartScopes.USER_ALL_READ,
            SmartScopes.USER_ALL_WRITE,
            SmartScopes.USER_ALL,
            "user/Patient.read",
            "user/Patient.write",
            "user/Observation.read",
            "user/Observation.write",
            "user/Condition.read",
            "user/Condition.write",
            "user/MedicationRequest.read",
            "user/MedicationRequest.write",
            
            // System scopes (backend services)
            SmartScopes.SYSTEM_ALL_READ,
            SmartScopes.SYSTEM_ALL_WRITE,
            SmartScopes.SYSTEM_ALL
        ));
        
        // Response types supported
        config.put("response_types_supported", Arrays.asList(
            "code" // Authorization code flow
        ));
        
        // Grant types supported
        config.put("grant_types_supported", Arrays.asList(
            "authorization_code",
            "client_credentials",
            "refresh_token"
        ));
        
        // PKCE (Proof Key for Code Exchange) support
        config.put("code_challenge_methods_supported", Arrays.asList(
            "S256" // SHA-256 based PKCE (required for public clients)
        ));
        
        // SMART capabilities
        config.put("capabilities", Arrays.asList(
            // Launch capabilities
            "launch-ehr",              // EHR launch (launched from within EHR)
            "launch-standalone",        // Standalone launch (launched independently)
            
            // Client capabilities
            "client-public",           // Support for public clients (no client secret)
            "client-confidential-symmetric", // Support for confidential clients with shared secret
            
            // Single sign-on
            "sso-openid-connect",      // OpenID Connect for SSO
            
            // Context capabilities
            "context-ehr-patient",     // EHR provides patient context
            "context-ehr-encounter",   // EHR provides encounter context
            "context-standalone-patient", // Standalone app can select patient
            
            // Permissions
            "permission-offline",      // Offline access (refresh tokens)
            "permission-patient",      // Patient-specific scopes
            "permission-user",         // User-specific scopes
            "permission-v2"            // SMART v2 scopes (granular permissions)
        ));
        
        logger.info("✅ Returned SMART configuration (issuer: {})", issuer);
        
        return ResponseEntity.ok(config);
    }
}

