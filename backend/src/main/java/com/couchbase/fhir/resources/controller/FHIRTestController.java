package com.couchbase.fhir.resources.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.MultiValueMap;
import com.couchbase.fhir.resources.service.FHIRTestGeneralService;
import com.couchbase.fhir.resources.service.FHIRTestCreateService;
import com.couchbase.fhir.resources.service.FHIRTestReadService;
import com.couchbase.fhir.resources.service.FHIRTestUpdateService;
import com.couchbase.fhir.resources.service.FHIRTestDeleteService;
import com.couchbase.fhir.resources.service.FHIRTestSearchService;
import com.couchbase.fhir.resources.service.FHIRBundleProcessingService;
import com.couchbase.fhir.resources.search.validation.FHIRSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FHIRSearchValidationException;
import com.couchbase.fhir.resources.search.validation.FHIROperationOutcomeBuilder;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URLDecoder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import ca.uhn.fhir.parser.IParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/fhir-test")
public class FHIRTestController {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestController.class);

    @Autowired
    private FHIRTestGeneralService generalService;
    
    @Autowired
    private FHIRTestCreateService createService;
    
    @Autowired
    private FHIRTestReadService readService;
    
    @Autowired
    private FHIRTestUpdateService updateService;
    
    @Autowired
    private FHIRTestDeleteService deleteService;
    
    @Autowired
    private FHIRTestSearchService searchService;
    
    @Autowired
    private FHIRBundleProcessingService bundleService;
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FHIRSearchParameterPreprocessor parameterPreprocessor;
    
    @Autowired
    private FHIROperationOutcomeBuilder outcomeBuilder;

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> response = new HashMap<>();
        response.put("module", "fhir");
        response.put("description", "FHIR API services - Test Implementation with Real Couchbase Data");
        response.put("endpoints", new String[]{
            "/api/fhir-test/{bucketName}/{resourceType}", 
            "/api/fhir-test/{bucketName}/{resourceType}/{id}"
        });
        response.put("version", "R4");
        response.put("note", "Using real Couchbase data via N1QL queries");
        response.put("dataSource", "Couchbase");
        response.put("urlPattern", "Bucket name is part of the path for multi-tenancy");
        response.put("searchCapabilities", "Advanced FHIR search with FTS support");
        return response;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities() {
        try {
            return ResponseEntity.ok(generalService.getCapabilities());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to get capabilities: " + e.getMessage())
            );
        }
    }

    // Dynamic Resource Search - uses advanced search service
    @GetMapping("/{bucketName}/{resourceType}")
    public ResponseEntity<?> searchResources(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam MultiValueMap<String, String> searchParams) {
        
        try {
            logger.info("🚀 FHIRTestController: Starting search for {} resources with params: {}", resourceType, searchParams);
            
            // Convert MultiValueMap to Map<String, List<String>> for validation
            Map<String, List<String>> allParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : searchParams.entrySet()) {
                allParams.put(entry.getKey(), entry.getValue());
            }
            
            // Remove framework parameters from validation
            Map<String, List<String>> validationParams = new HashMap<>(allParams);
            validationParams.remove("connectionName");
            validationParams.remove("bucketName");
            
            // STEP 1: Validate parameters BEFORE query execution
            logger.info("🔍 FHIRTestController: Running parameter validation...");
            try {
                parameterPreprocessor.validateSearchParameters(resourceType, validationParams);
                logger.info("✅ FHIRTestController: Parameter validation passed!");
            } catch (FHIRSearchValidationException validationException) {
                logger.warn("❌ FHIRTestController: Parameter validation failed: {}", validationException.getMessage());
                // Return FHIR-compliant error response
                String outcomeJson = outcomeBuilder.toJson(validationException.getOperationOutcome());
                return ResponseEntity.badRequest()
                    .header("Content-Type", "application/fhir+json")
                    .body(outcomeJson);
            }
            
            // STEP 2: Convert to legacy Map<String, String> format for existing search service
            // NOTE: This temporarily loses multiple values until we refactor the search service
            Map<String, String> cleanParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    // Take the first value for now - validation already caught conflicts
                    cleanParams.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            cleanParams.remove("connectionName");
            cleanParams.remove("bucketName");
            
            // Use advanced search service for all searches (now supports _revinclude)
            FHIRTestSearchService.SearchResult searchResult = searchService.searchResources(
                resourceType, cleanParams, connectionName, bucketName);
            
            // Create proper FHIR Bundle using HAPI FHIR utilities
            String baseUrl = "http://localhost:8080/api/fhir-test/" + bucketName;
            Bundle bundle = searchService.createSearchBundle(searchResult, baseUrl, cleanParams);
            
            // Convert Bundle to JSON string for response
            String bundleJson = searchService.getBundleAsJson(bundle);
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to search " + resourceType + ": " + e.getMessage())
            );
        }
    }

    // Dynamic Resource Search via POST - FHIR compliant search for Inferno and other test suites
    @PostMapping("/{bucketName}/{resourceType}/_search")
    public ResponseEntity<?> searchResourcesPost(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam Map<String, String> queryParams,
            @RequestBody(required = false) String formData) {
        
        try {
            // Start with query parameters
            Map<String, String> allParams = new HashMap<>(queryParams);
            
            // Parse form data if present (application/x-www-form-urlencoded)
            if (formData != null && !formData.trim().isEmpty()) {
                String[] pairs = formData.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = java.net.URLDecoder.decode(keyValue[0], "UTF-8");
                        String value = java.net.URLDecoder.decode(keyValue[1], "UTF-8");
                        allParams.put(key, value); // Form params override query params
                    }
                }
            }
            
            // Remove connection/bucket params from search parameters
            allParams.remove("connectionName");
            allParams.remove("bucketName");
            
            logger.info("🔍 POST Search request - Resource: {}, Parameters: {}", resourceType, allParams);
            
            // Use the same search service as GET (now supports _revinclude)
            FHIRTestSearchService.SearchResult searchResult = searchService.searchResources(
                resourceType, allParams, connectionName, bucketName);
            
            // Create proper FHIR Bundle using HAPI FHIR utilities
            String baseUrl = "http://localhost:8080/api/fhir-test/" + bucketName;
            Bundle bundle = searchService.createSearchBundle(searchResult, baseUrl, allParams);
            
            // Convert Bundle to JSON string for response
            String bundleJson = searchService.getBundleAsJson(bundle);
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
        } catch (Exception e) {
            logger.error("❌ POST search failed for {}: {}", resourceType, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to search " + resourceType + ": " + e.getMessage())
            );
        }
    }

    // Standard FHIR R4 pattern - POST search without bucket in path (uses default bucket)
    @PostMapping("/fhir/{resourceType}/_search")
    public ResponseEntity<?> searchResourcesPostFhir(
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam Map<String, String> queryParams,
            @RequestBody(required = false) String formData) {
        
        // Use default bucket "fhir" for standard FHIR R4 pattern
        return searchResourcesPost("fhir", resourceType, connectionName, queryParams, formData);
    }

    // Standard FHIR R4 pattern - GET search without bucket in path (uses default bucket)
    @GetMapping("/fhir/{resourceType}")
    public ResponseEntity<?> searchResourcesFhir(
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam MultiValueMap<String, String> searchParams) {
        
        // Use default bucket "fhir" for standard FHIR R4 pattern
        return searchResources("fhir", resourceType, connectionName, searchParams);
    }

    // Dynamic Resource by ID - handles any resource type
    @GetMapping("/{bucketName}/{resourceType}/{id}")
    public ResponseEntity<?> getResourceById(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @PathVariable String id,
            @RequestParam(required = false) String connectionName) {
        
        try {
            Map<String, Object> resource = readService.getResourceById(
                resourceType, id, connectionName, bucketName
            );
            
            if (resource == null) {
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to get " + resourceType + "/" + id + ": " + e.getMessage())
            );
        }
    }

    // Dynamic Resource Creation with FHIR validation - handles any resource type
    @PostMapping("/{bucketName}/{resourceType}")
    public ResponseEntity<?> createResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false, defaultValue = "strict") String validationMode,
            @RequestBody Map<String, Object> resourceData) {
        
        try {
            // Determine validation strictness
            boolean useLenientValidation = "lenient".equalsIgnoreCase(validationMode);
            
            logger.info("🚀 Creating {} resource with {} validation", resourceType, 
                useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)");
            
            Map<String, Object> result = createService.createResource(
                resourceType, connectionName, bucketName, resourceData, useLenientValidation
            );
            
            // Add validation mode info to response
            result.put("validationMode", useLenientValidation ? "lenient" : "strict");
            
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (RuntimeException e) {
            // If it's a validation error, return 400 Bad Request
            if (e.getMessage().contains("FHIR validation failed")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "FHIR Validation Failed");
                errorResponse.put("message", e.getMessage());
                errorResponse.put("resourceType", resourceType);
                errorResponse.put("validationMode", "lenient".equalsIgnoreCase(validationMode) ? "lenient" : "strict");
                errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }
            
            // Other runtime errors
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to create " + resourceType + ": " + e.getMessage())
            );
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to create " + resourceType + ": " + e.getMessage())
            );
        }
    }
    
    // FHIR Resource Validation endpoint - validates without creating
    @PostMapping("/{bucketName}/{resourceType}/validate")
    public ResponseEntity<?> validateResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false, defaultValue = "strict") String validationMode,
            @RequestBody Map<String, Object> resourceData) {
        
        try {
            // Determine validation strictness
            boolean useLenientValidation = "lenient".equalsIgnoreCase(validationMode);
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            
            logger.info("🔍 Validating {} resource with {} validation", resourceType, validationType);
            
            Map<String, Object> result = createService.validateResourceOnly(resourceType, resourceData, useLenientValidation);
            
            // Add validation mode info to response
            result.put("validationMode", useLenientValidation ? "lenient" : "strict");
            
            if ((Boolean) result.get("valid")) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
            }
            
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "FHIR Resource Validation Failed");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("resourceType", resourceType);
            errorResponse.put("validationMode", "lenient".equalsIgnoreCase(validationMode) ? "lenient" : "strict");
            errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // FHIR Bundle Transaction Processing - handles multiple resource types
    @PostMapping("/{bucketName}/Bundle")
    public ResponseEntity<String> processBundleTransaction(
            @PathVariable String bucketName,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false, defaultValue = "strict") String validationMode,
            @RequestBody String bundleJson) {
        
        try {
            // Determine validation strictness
            boolean useLenientValidation = "lenient".equalsIgnoreCase(validationMode);
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            
            logger.info("🔄 Processing FHIR Bundle transaction for bucket: {} with {} validation", bucketName, validationType);
            
            // Process the bundle and get proper FHIR Bundle response with specified validation
            Bundle responseBundle = bundleService.processBundleTransaction(bundleJson, connectionName, bucketName, useLenientValidation);
            
            // Convert to JSON and return with proper FHIR content type
            String responseJson = jsonParser.encodeResourceToString(responseBundle);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                .header("Content-Type", "application/fhir+json")
                .header("X-Validation-Mode", useLenientValidation ? "lenient" : "strict")
                .body(responseJson);
            
        } catch (RuntimeException e) {
            logger.error("❌ Bundle transaction processing failed: {}", e.getMessage(), e);
            return createErrorResponse(e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Bundle transaction processing failed: {}", e.getMessage(), e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    /**
     * Create FHIR OperationOutcome error response
     */
    private ResponseEntity<String> createErrorResponse(String errorMessage) {
        try {
            OperationOutcome outcome = new OperationOutcome();
            
            OperationOutcome.OperationOutcomeIssueComponent issue = new OperationOutcome.OperationOutcomeIssueComponent();
            issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
            issue.setCode(OperationOutcome.IssueType.PROCESSING);
            issue.setDiagnostics(errorMessage);
            
            outcome.addIssue(issue);
            
            String outcomeJson = jsonParser.encodeResourceToString(outcome);
            
            return ResponseEntity.badRequest()
                .header("Content-Type", "application/fhir+json")
                .body(outcomeJson);
                
        } catch (Exception e) {
            // Fallback to simple JSON if FHIR encoding fails
            return ResponseEntity.internalServerError()
                .header("Content-Type", "application/json")
                .body("{\"error\": \"" + errorMessage + "\"}");
        }
    }
    


    // Note: Now using proper HAPI FHIR Bundle creation via searchService.createSearchBundle()
}
