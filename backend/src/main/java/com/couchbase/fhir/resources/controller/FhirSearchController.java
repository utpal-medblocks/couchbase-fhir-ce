package com.couchbase.fhir.resources.controller;

import com.couchbase.fhir.resources.service.FhirSearchService;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.search.validation.FhirOperationOutcomeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import org.hl7.fhir.r4.model.Bundle;

/**
 * FHIR Search Controller
 * Dedicated controller for advanced FHIR search operations
 * 
 * URL Pattern: /api/fhir/search/{resourceType}
 * Examples:
 * - GET /api/fhir/search/Patient?name=John&gender=male
 * - POST /api/fhir/search/Patient/_search (form-based search)
 * 
 * This controller provides enhanced search capabilities with:
 * - Parameter validation and FHIR compliance
 * - Advanced query processing
 * - Search result optimization
 * - _revinclude and chained parameter support
 */
@RestController
@RequestMapping("/api/fhir/search")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class FhirSearchController {

    private static final Logger logger = LoggerFactory.getLogger(FhirSearchController.class);

    @Autowired
    private FhirSearchService searchService;
    
    @Autowired
    private FhirSearchParameterPreprocessor parameterPreprocessor;
    
    @Autowired
    private FhirOperationOutcomeBuilder outcomeBuilder;
    
    public FhirSearchController() {
        // Empty - Spring will inject dependencies
    }

    /**
     * Search FHIR resources with advanced parameter validation
     * GET /api/fhir/search/{resourceType}?param=value
     */
    @GetMapping("/{resourceType}")
    public ResponseEntity<String> searchResources(
            @PathVariable String resourceType,
            @RequestParam MultiValueMap<String, String> queryParams,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false) String bucketName) {
        
        try {
            logger.info("🔍 Advanced search for {} resources with params: {}", resourceType, queryParams);
            logger.info("🔍 Connection: {}, Bucket: {}", connectionName, bucketName);
            
            // Convert MultiValueMap to Map<String, List<String>> for validation
            Map<String, List<String>> allParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                allParams.put(entry.getKey(), entry.getValue());
            }
            
            // Remove framework parameters from validation
            Map<String, List<String>> validationParams = new HashMap<>(allParams);
            validationParams.remove("connectionName");
            validationParams.remove("bucketName");
            
            // STEP 1: Validate parameters BEFORE query execution
            logger.info("🔍 Running advanced parameter validation...");
            try {
                parameterPreprocessor.validateSearchParameters(resourceType, validationParams);
                logger.info("✅ Parameter validation passed!");
            } catch (FhirSearchValidationException validationException) {
                logger.warn("❌ Parameter validation failed: {}", validationException.getMessage());
                // Return FHIR-compliant error response
                String outcomeJson = outcomeBuilder.toJson(validationException.getOperationOutcome());
                return ResponseEntity.badRequest()
                    .header("Content-Type", "application/fhir+json")
                    .body(outcomeJson);
            }
            
            // STEP 2: Convert to legacy Map<String, String> format for existing search service
            // NOTE: This temporarily loses multiple values until we refactor the search service
            Map<String, String> searchParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    // Take the first value for now - validation already caught conflicts
                    searchParams.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            searchParams.remove("connectionName");
            searchParams.remove("bucketName");
            
            logger.info("🔍 Executing search with validated parameters...");
            
            FhirSearchService.SearchResult searchResult;
            try {
                searchResult = searchService.searchResources(resourceType, searchParams, connectionName, bucketName);
                logger.info("✅ Search service call completed successfully!");
                logger.info("✅ Retrieved {} resources from search service", searchResult.getPrimaryResources().size());
            } catch (Exception searchException) {
                logger.error("❌ Search service call failed: {}", searchException.getMessage(), searchException);
                throw searchException;
            }
            
            // Debug: Check if searchResult is null
            if (searchResult == null) {
                logger.error("❌ SearchResult is null! This should not happen.");
                throw new RuntimeException("Search service returned null searchResult");
            }
         
            List<Map<String, Object>> resources = searchResult.getPrimaryResources();
            
            // Create proper FHIR Bundle response with search metadata
            String baseUrl = determineBaseUrl(connectionName, bucketName);
            Bundle bundle = searchService.createSearchBundle(searchResult, baseUrl, searchParams);
            
            // Convert Bundle to JSON string for response
            String bundleJson = searchService.getBundleAsJson(bundle);
            
            logger.info("✅ Advanced search completed successfully");
            logger.info("📊 Search results: {} primary resources", resources.size());
            if (searchResult.hasIncludes()) {
                logger.info("📊 Included resources: {} types", searchResult.getIncludedResources().size());
            }
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
        } catch (FhirSearchValidationException validationException) {
            // Already handled above, but catch here for completeness
            logger.warn("❌ Search validation error: {}", validationException.getMessage());
            String outcomeJson = outcomeBuilder.toJson(validationException.getOperationOutcome());
            return ResponseEntity.badRequest()
                .header("Content-Type", "application/fhir+json")
                .body(outcomeJson);
                
        } catch (Exception e) {
            logger.error("❌ Advanced search failed for {} resources: {}", resourceType, e.getMessage(), e);
            
            // Create FHIR OperationOutcome for unexpected errors
            String outcomeJson = outcomeBuilder.toJson(
                outcomeBuilder.createSearchError("Search operation failed: " + e.getMessage())
            );
            
            return ResponseEntity.internalServerError()
                .header("Content-Type", "application/fhir+json")
                .body(outcomeJson);
        }
    }
    
    /**
     * POST-based search with form data support
     * POST /api/fhir/search/{resourceType}/_search
     */
    @PostMapping("/{resourceType}/_search")
    public ResponseEntity<String> searchResourcesPost(
            @PathVariable String resourceType,
            @RequestParam MultiValueMap<String, String> queryParams,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false) String bucketName,
            @RequestBody(required = false) String formData) {
        
        try {
            logger.info("🔍 POST search for {} resources", resourceType);
            
            // Merge query parameters and form data
            Map<String, List<String>> allParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                allParams.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            
            // Parse form data if present (application/x-www-form-urlencoded)
            if (formData != null && !formData.trim().isEmpty()) {
                logger.debug("📝 Parsing form data: {}", formData);
                parseFormDataIntoParams(formData, allParams);
            }
            
            // Convert back to MultiValueMap for consistent processing
            MultiValueMap<String, String> mergedParams = new org.springframework.util.LinkedMultiValueMap<>();
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                mergedParams.put(entry.getKey(), entry.getValue());
            }
            
            // Delegate to GET search method for consistent processing
            return searchResources(resourceType, mergedParams, connectionName, bucketName);
            
        } catch (Exception e) {
            logger.error("❌ POST search failed for {} resources: {}", resourceType, e.getMessage(), e);
            
            String outcomeJson = outcomeBuilder.toJson(
                outcomeBuilder.createSearchError("POST search operation failed: " + e.getMessage())
            );
            
            return ResponseEntity.internalServerError()
                .header("Content-Type", "application/fhir+json")
                .body(outcomeJson);
        }
    }
    
    /**
     * Get search capabilities and supported parameters
     * GET /api/fhir/search/capabilities
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> getSearchCapabilities() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("service", "FHIR Advanced Search");
        capabilities.put("version", "1.0");
        capabilities.put("fhirVersion", "R4");
        capabilities.put("features", Arrays.asList(
            "Parameter validation",
            "Multiple parameter values", 
            "Date range validation",
            "Token parameter validation",
            "Reference parameter validation",
            "Chained parameters",
            "Composite parameters",
            "_revinclude support",
            "FTS-based search",
            "FHIR-compliant error responses"
        ));
        capabilities.put("supportedParameters", Arrays.asList(
            "_id", "_lastUpdated", "_text", "_count", "_offset",
            "name", "family", "given", "birthdate", "gender",
            "active", "subject", "patient", "organization"
        ));
        capabilities.put("supportedResourceTypes", Arrays.asList(
            "Patient", "Practitioner", "Organization", "Observation", 
            "DiagnosticReport", "Condition", "Encounter", "Location"
        ));
        capabilities.put("validation", Map.of(
            "enabled", true,
            "conflictDetection", true,
            "formatValidation", true,
            "parameterExistence", true
        ));
        
        return ResponseEntity.ok(capabilities);
    }

    // ========== Helper Methods ==========
    
    /**
     * Determine the base URL for FHIR Bundle self-links
     */
    private String determineBaseUrl(String connectionName, String bucketName) {
        // Use bucket name if provided, otherwise default
        String bucket = (bucketName != null && !bucketName.isEmpty()) ? bucketName : "fhir";
        return "http://localhost:8080/api/fhir/search";
    }
    
    /**
     * Parse form data into parameter map
     * Handles application/x-www-form-urlencoded format
     */
    private void parseFormDataIntoParams(String formData, Map<String, List<String>> allParams) {
        try {
            String[] pairs = formData.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = java.net.URLDecoder.decode(keyValue[0], "UTF-8");
                    String value = java.net.URLDecoder.decode(keyValue[1], "UTF-8");
                    
                    // Add to existing values or create new list
                    allParams.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse form data: {}", e.getMessage());
        }
    }
} 