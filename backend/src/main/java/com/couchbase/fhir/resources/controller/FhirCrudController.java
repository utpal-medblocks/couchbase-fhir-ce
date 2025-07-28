package com.couchbase.fhir.resources.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.MultiValueMap;
import com.couchbase.fhir.resources.service.FhirCapabilityService;
import com.couchbase.fhir.resources.service.FhirCreateService;
import com.couchbase.fhir.resources.service.FhirReadService;
import com.couchbase.fhir.resources.service.FhirUpdateService;
import com.couchbase.fhir.resources.service.FhirDeleteService;
import com.couchbase.fhir.resources.service.FhirSearchService;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.search.validation.FhirOperationOutcomeBuilder;

import java.util.*;
import java.util.Set;
import java.util.HashSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URLDecoder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.SummaryEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FHIR CRUD Controller
 * Handles Create, Read, Update, Delete operations and search for FHIR resources
 * 
 * URL Pattern: /api/fhir/{bucket}/{resourceType}[/{id}]
 * Examples:
 * - GET /api/fhir/us-core/Patient/123 (read)
 * - POST /api/fhir/us-core/Patient (create)  
 * - PUT /api/fhir/us-core/Patient/123 (update)
 * - DELETE /api/fhir/us-core/Patient/123 (delete)
 * - GET /api/fhir/us-core/Patient?name=John (search)
 */
@RestController
@RequestMapping("/api/fhir")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class FhirCrudController {

    private static final Logger logger = LoggerFactory.getLogger(FhirCrudController.class);

    @Autowired
    private FhirCapabilityService generalService;
    
    @Autowired
    private FhirCreateService createService;
    
    @Autowired
    private FhirReadService readService;
    
    @Autowired
    private FhirUpdateService updateService;
    
    @Autowired
    private FhirDeleteService deleteService;
    
    @Autowired
    private FhirSearchService searchService;
    
    @Autowired
    private FhirBundleProcessingService bundleService;
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FhirSearchParameterPreprocessor parameterPreprocessor;
    
    @Autowired
    private FhirOperationOutcomeBuilder outcomeBuilder;

    /**
     * Get FHIR server information
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> response = new HashMap<>();
        response.put("module", "fhir");
        response.put("description", "Production FHIR API Services with Couchbase Backend");
        response.put("endpoints", Arrays.asList(
            "GET /api/fhir/{bucket}/{resourceType} - Search resources",
            "GET /api/fhir/{bucket}/{resourceType}/{id} - Read resource",
            "POST /api/fhir/{bucket}/{resourceType} - Create resource",
            "PUT /api/fhir/{bucket}/{resourceType}/{id} - Update resource",
            "DELETE /api/fhir/{bucket}/{resourceType}/{id} - Delete resource"
        ));
        response.put("version", "R4");
        response.put("fhirVersion", "4.0.1");
        response.put("dataSource", "Couchbase");
        response.put("searchCapabilities", "Advanced FHIR search with FTS support");
        response.put("validationEnabled", true);
        return ResponseEntity.ok(response);
    }

    /**
     * Get FHIR server capabilities
     */
    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities() {
        try {
            return ResponseEntity.ok(generalService.getCapabilities());
        } catch (Exception e) {
            logger.error("Failed to get capabilities: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to get capabilities: " + e.getMessage())
            );
        }
    }

    // ========== READ Operations ==========
    
    /**
     * Read FHIR resource by ID
     * GET /api/fhir/{bucket}/{resourceType}/{id}
     */
    @GetMapping("/{bucketName}/{resourceType}/{id}")
    public ResponseEntity<?> readResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @PathVariable String id,
            @RequestParam(required = false) String connectionName) {
        
        try {
            logger.info("📖 Reading {} resource with ID: {} from bucket: {}", resourceType, id, bucketName);
            
            Map<String, Object> resource = readService.getResourceById(resourceType, id, connectionName, bucketName);
            
            if (resource == null) {
                logger.warn("❌ Resource not found: {}/{}", resourceType, id);
                return ResponseEntity.notFound().build();
            }
            
            logger.info("✅ Successfully read {} resource: {}", resourceType, id);
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(resource);
                
        } catch (Exception e) {
            logger.error("❌ Failed to read {}/{}: {}", resourceType, id, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to read resource: " + e.getMessage())
            );
        }
    }

    // ========== SEARCH Operations ==========
    
    /**
     * Search FHIR resources with parameters
     * GET /api/fhir/{bucket}/{resourceType}?param=value
     */
    @GetMapping("/{bucketName}/{resourceType}")
    public ResponseEntity<?> searchResources(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam MultiValueMap<String, String> searchParams) {
        
        try {
            logger.info("🔍 Searching {} resources in bucket: {} with params: {}", resourceType, bucketName, searchParams);
            
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
            logger.debug("🔍 Running parameter validation...");
            try {
                parameterPreprocessor.validateSearchParameters(resourceType, validationParams);
                logger.debug("✅ Parameter validation passed!");
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
            Map<String, String> cleanParams = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    // Take the first value for now - validation already caught conflicts
                    cleanParams.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            cleanParams.remove("connectionName");
            cleanParams.remove("bucketName");
            
            // STEP 3: Parse _summary and _elements parameters
            SummaryEnum summaryMode = parseSummaryParameter(cleanParams);
            Set<String> elements = parseElementsParameter(cleanParams);
            
            if (summaryMode != null) {
                logger.info("🔍 Summary mode detected: {}", summaryMode);
            }
            if (elements != null) {
                logger.info("🔍 Elements filter detected: {}", elements);
            }
            
            // STEP 4: Execute search with validated parameters
            FhirSearchService.SearchResult searchResult = searchService.searchResources(
                resourceType, cleanParams, connectionName, bucketName);
            
            // STEP 5: Create proper FHIR Bundle response with summary and elements support
            String baseUrl = "http://localhost:8080/api/fhir/" + bucketName;
            Bundle bundle = searchService.createSearchBundle(searchResult, baseUrl, cleanParams, summaryMode, elements);
            
            // STEP 6: Return JSON response with summary mode and elements
            String bundleJson = searchService.getBundleAsJson(bundle, summaryMode, elements, resourceType);
            String responseDetails = "";
            if (summaryMode != null || elements != null) {
                List<String> details = new ArrayList<>();
                if (summaryMode != null) details.add("summary: " + summaryMode);
                if (elements != null) details.add("elements: " + elements.size() + " fields");
                responseDetails = " (" + String.join(", ", details) + ")";
            }
            
            logger.info("✅ Search completed - Found {} {} resources{}", 
                       searchResult.getPrimaryResources().size(), resourceType, responseDetails);
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
        } catch (Exception e) {
            logger.error("❌ Search failed for {} resources: {}", resourceType, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Search failed: " + e.getMessage())
            );
        }
    }

    /**
     * Search FHIR resources via POST (FHIR-compliant)
     * POST /api/fhir/{bucket}/{resourceType}/_search
     */
    @PostMapping("/{bucketName}/{resourceType}/_search")
    public ResponseEntity<?> searchResourcesPost(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam Map<String, String> queryParams,
            @RequestBody(required = false) String formData) {
        
        try {
            logger.info("🔍 POST Search for {} resources in bucket: {}", resourceType, bucketName);
            
            // Start with query parameters
            Map<String, String> allParams = new HashMap<>(queryParams);
            
            // Parse form data if present (application/x-www-form-urlencoded)
            if (formData != null && !formData.trim().isEmpty()) {
                String[] pairs = formData.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = URLDecoder.decode(keyValue[0], "UTF-8");
                        String value = URLDecoder.decode(keyValue[1], "UTF-8");
                        allParams.put(key, value); // Form params override query params
                    }
                }
            }
            
            // Remove connection/bucket params from search parameters
            allParams.remove("connectionName");
            allParams.remove("bucketName");
            
            logger.debug("🔍 POST Search parameters: {}", allParams);
            
            // Parse _summary and _elements parameters
            SummaryEnum summaryMode = parseSummaryParameter(allParams);
            Set<String> elements = parseElementsParameter(allParams);
            
            if (summaryMode != null) {
                logger.info("🔍 POST Search summary mode detected: {}", summaryMode);
            }
            if (elements != null) {
                logger.info("🔍 POST Search elements filter detected: {}", elements);
            }
            
            // Use the same search service as GET (supports _revinclude)
            FhirSearchService.SearchResult searchResult = searchService.searchResources(
                resourceType, allParams, connectionName, bucketName);
            
            // Create proper FHIR Bundle using HAPI FHIR utilities with summary and elements support
            String baseUrl = "http://localhost:8080/api/fhir/" + bucketName;
            Bundle bundle = searchService.createSearchBundle(searchResult, baseUrl, allParams, summaryMode, elements);
            
            // Convert Bundle to JSON string for response with summary mode and elements
            String bundleJson = searchService.getBundleAsJson(bundle, summaryMode, elements, resourceType);
            String postResponseDetails = "";
            if (summaryMode != null || elements != null) {
                List<String> postDetails = new ArrayList<>();
                if (summaryMode != null) postDetails.add("summary: " + summaryMode);
                if (elements != null) postDetails.add("elements: " + elements.size() + " fields");
                postResponseDetails = " (" + String.join(", ", postDetails) + ")";
            }
            
            logger.info("✅ POST Search completed - Found {} {} resources{}", 
                       searchResult.getPrimaryResources().size(), resourceType, postResponseDetails);
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
        } catch (Exception e) {
            logger.error("❌ POST Search failed for {} resources: {}", resourceType, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "POST Search failed: " + e.getMessage())
            );
        }
    }

    // ========== Standard FHIR R4 Endpoints (default bucket) ==========
    
    /**
     * Standard FHIR R4 pattern - GET search without bucket in path (uses default bucket "fhir")
     * GET /api/fhir/{resourceType}?param=value
     */
    @GetMapping("/fhir/{resourceType}")
    public ResponseEntity<?> searchResourcesFhir(
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam MultiValueMap<String, String> searchParams) {
        
        // Use default bucket "fhir" for standard FHIR R4 pattern
        return searchResources("fhir", resourceType, connectionName, searchParams);
    }

    /**
     * Standard FHIR R4 pattern - POST search without bucket in path (uses default bucket "fhir")
     * POST /api/fhir/{resourceType}/_search
     */
    @PostMapping("/fhir/{resourceType}/_search")
    public ResponseEntity<?> searchResourcesFhirPost(
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestParam Map<String, String> queryParams,
            @RequestBody(required = false) String formData) {
        
        return searchResourcesPost("fhir", resourceType, connectionName, queryParams, formData);
    }

    // ========== CREATE Operations ==========
    
    /**
     * Create FHIR resource
     * POST /api/fhir/{bucket}/{resourceType}
     */
    @PostMapping("/{bucketName}/{resourceType}")
    public ResponseEntity<?> createResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestBody String resourceJson,
            @RequestParam(required = false) String connectionName) {
        
        try {
            logger.info("🆕 Creating {} resource in bucket: {}", resourceType, bucketName);
            
            Map<String, Object> result = createService.createResourceFromJson(
                resourceType, connectionName, bucketName, resourceJson);
            
            logger.info("✅ Successfully created {} resource", resourceType);
            return ResponseEntity.status(HttpStatus.CREATED)
                .header("Content-Type", "application/fhir+json")
                .body(result);
                
        } catch (Exception e) {
            logger.error("❌ Failed to create {} resource: {}", resourceType, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to create resource: " + e.getMessage())
            );
        }
    }

    // ========== UPDATE Operations ==========
    
    /**
     * Update FHIR resource
     * PUT /api/fhir/{bucket}/{resourceType}/{id}
     */
    @PutMapping("/{bucketName}/{resourceType}/{id}")
    public ResponseEntity<?> updateResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @PathVariable String id,
            @RequestBody String resourceJson,
            @RequestParam(required = false) String connectionName) {
        
        try {
            logger.info("🔄 Updating {} resource with ID: {} in bucket: {}", resourceType, id, bucketName);
            
            // Parse JSON to Map for update service
            Map<String, Object> resourceData = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(resourceJson, Map.class);
            
            Map<String, Object> result = updateService.updateResource(
                resourceType, id, connectionName, bucketName, resourceData);
            
            logger.info("✅ Successfully updated {} resource: {}", resourceType, id);
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(result);
                
        } catch (Exception e) {
            logger.error("❌ Failed to update {}/{}: {}", resourceType, id, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to update resource: " + e.getMessage())
            );
        }
    }

    // ========== DELETE Operations ==========
    
    /**
     * Delete FHIR resource
     * DELETE /api/fhir/{bucket}/{resourceType}/{id}
     */
    @DeleteMapping("/{bucketName}/{resourceType}/{id}")
    public ResponseEntity<?> deleteResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @PathVariable String id,
            @RequestParam(required = false) String connectionName) {
        
        try {
            logger.info("🗑️  Deleting {} resource with ID: {} from bucket: {}", resourceType, id, bucketName);
            
            Map<String, Object> deleteResult = deleteService.deleteResource(resourceType, id, connectionName, bucketName);
            
            // Check if deletion was successful (assuming success field in result)
            boolean deleted = deleteResult != null && 
                (Boolean.TRUE.equals(deleteResult.get("success")) || deleteResult.containsKey("id"));
            
            if (deleted) {
                logger.info("✅ Successfully deleted {} resource: {}", resourceType, id);
                return ResponseEntity.noContent().build();
            } else {
                logger.warn("❌ Resource not found for deletion: {}/{}", resourceType, id);
                return ResponseEntity.notFound().build();
            }
                
        } catch (Exception e) {
            logger.error("❌ Failed to delete {}/{}: {}", resourceType, id, e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to delete resource: " + e.getMessage())
            );
        }
    }

    // ========== BUNDLE Operations ==========
    
    /**
     * Process FHIR Bundle (transaction/batch)
     * POST /api/fhir/{bucket}
     */
    @PostMapping("/{bucketName}")
    public ResponseEntity<?> processBundle(
            @PathVariable String bucketName,
            @RequestBody String bundleJson,
            @RequestParam(required = false) String connectionName) {
        
        try {
            logger.info("📦 Processing FHIR Bundle in bucket: {}", bucketName);
            
            Bundle resultBundle = bundleService.processBundleTransaction(bundleJson, connectionName, bucketName);
            
            // Convert Bundle back to JSON string  
            String result = jsonParser.encodeResourceToString(resultBundle);
            
            logger.info("✅ Successfully processed FHIR Bundle");
            return ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(result);
                
        } catch (Exception e) {
            logger.error("❌ Failed to process FHIR Bundle: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to process bundle: " + e.getMessage())
            );
        }
    }
    
    /**
     * Parse _summary parameter from search parameters
     */
    private SummaryEnum parseSummaryParameter(Map<String, String> searchParams) {
        String summaryValue = searchParams.get("_summary");
        if (summaryValue == null || summaryValue.isEmpty()) {
            return null;
        }
        
        try {
            switch (summaryValue.toLowerCase()) {
                case "true":
                    return SummaryEnum.TRUE;
                case "false":
                    return SummaryEnum.FALSE;
                case "text":
                    return SummaryEnum.TEXT;
                case "data":
                    return SummaryEnum.DATA;
                case "count":
                    return SummaryEnum.COUNT;
                default:
                    logger.warn("Unknown _summary value: {}, defaulting to false", summaryValue);
                    return SummaryEnum.FALSE;
            }
        } catch (Exception e) {
            logger.warn("Failed to parse _summary parameter: {}, defaulting to false", summaryValue);
            return SummaryEnum.FALSE;
        }
    }
    
    /**
     * Parse _elements parameter from search parameters
     */
    private Set<String> parseElementsParameter(Map<String, String> searchParams) {
        String elementsValue = searchParams.get("_elements");
        if (elementsValue == null || elementsValue.isEmpty()) {
            return null;
        }
        
        try {
            // Split by comma and trim whitespace
            Set<String> elements = new HashSet<>();
            String[] elementArray = elementsValue.split(",");
            
            for (String element : elementArray) {
                String trimmedElement = element.trim();
                if (!trimmedElement.isEmpty() && !trimmedElement.equals("*")) {
                    elements.add(trimmedElement);
                }
            }
            
            // If only "*" was specified or no valid elements, return null (means all elements)
            if (elements.isEmpty()) {
                return null;
            }
            
            logger.debug("🔍 Parsed _elements parameter: {}", elements);
            return elements;
            
        } catch (Exception e) {
            logger.warn("Failed to parse _elements parameter: {}, returning all elements", elementsValue);
            return null;
        }
    }
} 