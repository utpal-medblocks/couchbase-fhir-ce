package com.couchbase.fhir.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OAuth 2.0 Consent Controller
 * 
 * Handles the consent screen where users approve or deny access to their data.
 * Required for SMART on FHIR authorization flow.
 */
@Controller
public class ConsentController {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsentController.class);
    
    private final RegisteredClientRepository clientRepository;
    
    public ConsentController(RegisteredClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }
    
    /**
     * Show consent page
     * GET /consent - Custom consent page for SMART on FHIR authorization
     */
    @GetMapping("/consent")
    public String consent(
            Principal principal,
            Model model,
            HttpServletResponse response,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(value = OAuth2ParameterNames.REDIRECT_URI, required = false) String redirectUri,
            @RequestParam(value = OAuth2ParameterNames.RESPONSE_TYPE, required = false, defaultValue = "code") String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod) {
        
        // Prevent browser from caching the consent page HTML
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        logger.info("🔐 Consent requested for client: {} by user: {}", clientId, principal.getName());
        
        // Get client details
        RegisteredClient client = clientRepository.findByClientId(clientId);
        if (client == null) {
            logger.error("❌ Client not found: {}", clientId);
            model.addAttribute("error", "Invalid client");
            return "error";
        }
        
        // Parse requested scopes
        Set<String> requestedScopes = Arrays.stream(scope.split(" "))
                .collect(Collectors.toSet());
        
        // Build scope list with descriptions
        List<Map<String, String>> scopeList = new ArrayList<>();
        for (String scopeName : requestedScopes) {
            Map<String, String> scopeInfo = new HashMap<>();
            scopeInfo.put("scope", scopeName);
            scopeInfo.put("description", getScopeDescription(scopeName));
            scopeList.add(scopeInfo);
        }
        
        // Add model attributes for the consent page
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("clientName", client.getClientName() != null ? client.getClientName() : clientId);
        model.addAttribute("clientUri", client.getClientSettings().getTokenEndpointAuthenticationSigningAlgorithm());
        model.addAttribute("scopes", scopeList);
        model.addAttribute("scopeString", scope);
        model.addAttribute("client_id", clientId);
        model.addAttribute("state", state);
        model.addAttribute("redirect_uri", redirectUri);
        model.addAttribute("response_type", responseType);
        model.addAttribute("code_challenge", codeChallenge);
        model.addAttribute("code_challenge_method", codeChallengeMethod);
        
        return "consent";
    }
    
    /**
     * Get human-readable description for a SMART scope
     */
    private String getScopeDescription(String scope) {
        // SMART on FHIR scope descriptions
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("openid", "Verify your identity");
        descriptions.put("profile", "Access your profile information");
        descriptions.put("fhirUser", "Know which user you are");
        descriptions.put("launch/patient", "Know which patient record to access");
        descriptions.put("offline_access", "Access your data when you're not using the app");
        descriptions.put("online_access", "Access your data only when you're using the app");
        descriptions.put("patient/*.rs", "Read and search all your health data");
        descriptions.put("patient/*.cud", "Create, update, and delete your health data");
        descriptions.put("patient/*.cruds", "Full access to your health data");
        // Legacy v1 format support
        descriptions.put("patient/*.read", "Read all your health data");
        descriptions.put("patient/*.write", "Create and update your health data");
        descriptions.put("patient/*.*", "Full access to your health data");
        descriptions.put("user/*.read", "Read health data on your behalf");
        descriptions.put("user/*.write", "Create and update health data on your behalf");
        descriptions.put("user/*.*", "Full access to health data on your behalf");
        
        // US Core resource-specific scopes
        if (scope.startsWith("patient/")) {
            String resource = scope.substring(8).split("\\.")[0];
            if (scope.endsWith(".read") || scope.endsWith(".rs")) {
                return "Read your " + formatResourceName(resource) + " data";
            } else if (scope.endsWith(".write")) {
                return "Create and update your " + formatResourceName(resource) + " data";
            }
        }
        
        return descriptions.getOrDefault(scope, "Access: " + scope);
    }
    
    /**
     * Format FHIR resource names for display
     */
    private String formatResourceName(String resource) {
        return switch (resource) {
            case "AllergyIntolerance" -> "allergies";
            case "MedicationRequest" -> "medications";
            case "DiagnosticReport" -> "lab results";
            case "DocumentReference" -> "documents";
            case "Immunization" -> "immunizations";
            case "Observation" -> "observations";
            case "Condition" -> "conditions";
            case "Procedure" -> "procedures";
            case "Encounter" -> "encounters";
            case "Patient" -> "patient information";
            case "Practitioner" -> "practitioner information";
            case "Organization" -> "organization information";
            default -> resource.toLowerCase();
        };
    }
}

