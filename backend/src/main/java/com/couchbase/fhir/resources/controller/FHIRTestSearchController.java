package com.couchbase.fhir.resources.controller;

import com.couchbase.fhir.resources.service.FHIRTestSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import org.hl7.fhir.r4.model.Bundle;

@RestController
@RequestMapping("/api/fhir-search")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class FHIRTestSearchController {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestSearchController.class);

    @Autowired
    private FHIRTestSearchService searchService;
    
    public FHIRTestSearchController() {
        // Empty - Spring will inject dependencies
    }

    /**
     * Search FHIR resources with parameters
     * Example: GET /api/fhir-search/Patient?name=John&gender=male
     */
    @GetMapping("/{resourceType}")
    public ResponseEntity<String> searchResources(
            @PathVariable String resourceType,
            @RequestParam Map<String, String> queryParams,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false) String bucketName) {
        
        try {
            logger.info("🚀 Controller: Starting search for {} resources with params: {}", resourceType, queryParams);
            logger.info("🚀 Controller: Connection: {}, Bucket: {}", connectionName, bucketName);
            
            // Remove connection/bucket params from search parameters
            Map<String, String> searchParams = new HashMap<>(queryParams);
            searchParams.remove("connectionName");
            searchParams.remove("bucketName");
            
            logger.info("🔍 Controller: About to call searchService.searchResources()");
            
            List<Map<String, Object>> resources;
            try {
                resources = searchService.searchResources(resourceType, searchParams, connectionName, bucketName);
                logger.info("✅ Controller: Search service call completed successfully!");
                logger.info("✅ Controller: Retrieved {} resources from search service", resources.size());
            } catch (Exception searchException) {
                logger.error("❌ Controller: Search service call failed: {}", searchException.getMessage(), searchException);
                throw searchException;
            }
            
            // Debug: Check if resources is null
            if (resources == null) {
                logger.error("❌ Controller: Resources is null! This should not happen.");
                throw new RuntimeException("Search service returned null resources");
            }
            
            logger.info("🔍 Controller: About to process {} resources", resources.size());
            
            // Debug: Check first resource structure
            if (!resources.isEmpty()) {
                Map<String, Object> firstResource = resources.get(0);
                logger.info("📋 Controller: First resource keys: {}", firstResource.keySet());
                logger.info("📋 Controller: First resource ID: {}", firstResource.get("id"));
                logger.info("📋 Controller: First resource type: {}", firstResource.get("resourceType"));
            }
            
            if (resources.isEmpty()) {
                logger.info("📭 No resources found, will create empty bundle");
            }
            
            // Create proper FHIR Bundle using HAPI FHIR
            String baseUrl = "http://localhost:8080/api/fhir-test/" + (bucketName != null ? bucketName : "fhir");
            logger.info("📦 Controller: Creating Bundle with baseUrl: {}", baseUrl);
            
            Bundle bundle;
            try {
                bundle = searchService.createSearchBundle(resourceType, resources, baseUrl, searchParams);
                logger.info("✅ Controller: Bundle created successfully with {} entries", bundle.getEntry().size());
            } catch (Exception bundleException) {
                logger.error("❌ Controller: Bundle creation failed: {}", bundleException.getMessage(), bundleException);
                throw new RuntimeException("Bundle creation failed: " + bundleException.getMessage(), bundleException);
            }
            
            // Debug: Check bundle structure before serialization
            logger.info("Bundle before serialization: {} entries", bundle.getEntry().size());
            if (!bundle.getEntry().isEmpty()) {
                Bundle.BundleEntryComponent firstEntry = bundle.getEntry().get(0);
                logger.info("First entry has search: {}", firstEntry.hasSearch());
                if (firstEntry.hasSearch()) {
                    logger.info("Search mode: {}", firstEntry.getSearch().getMode());
                }
            }
            
            // Convert Bundle to JSON string
            logger.info("🔄 Controller: Serializing Bundle to JSON...");
            String bundleJson = searchService.getBundleAsJson(bundle);
            logger.info("✅ Controller: Bundle serialized successfully");
            logger.info("Serialized Bundle contains 'search': {}", bundleJson.contains("\"search\""));
            
            // Show a snippet of the serialized JSON for debugging
            String jsonSnippet = bundleJson.length() > 500 ? bundleJson.substring(0, 500) + "..." : bundleJson;
            logger.info("Bundle JSON snippet: {}", jsonSnippet);
            
            ResponseEntity<String> response = ResponseEntity.ok()
                .header("Content-Type", "application/fhir+json")
                .body(bundleJson);
            
            logger.info("🚀 Controller: Returning response with status: {}", response.getStatusCode());
            return response;
            
        } catch (Exception e) {
            logger.error("Error searching {} resources: {}", resourceType, e.getMessage(), e);
            
            // Create FHIR OperationOutcome for error response
            String errorJson = "{\n" +
                "  \"resourceType\": \"OperationOutcome\",\n" +
                "  \"issue\": [{\n" +
                "    \"severity\": \"error\",\n" +
                "    \"code\": \"processing\",\n" +
                "    \"diagnostics\": \"Search failed: " + e.getMessage() + "\"\n" +
                "  }]\n" +
                "}";
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/fhir+json")
                .body(errorJson);
        }
    }

    /**
     * Get search capabilities - what search parameters are supported
     */
    @GetMapping("/capabilities/{resourceType}")
    public ResponseEntity<Map<String, Object>> getSearchCapabilities(@PathVariable String resourceType) {
        try {
            Map<String, Object> capabilities = new HashMap<>();
            capabilities.put("resourceType", resourceType);
            capabilities.put("supportedParameters", Arrays.asList(
                "name", "family", "given", "identifier", "gender", "birthdate", 
                "phone", "email", "address", "organization", "_text", "_id"
            ));
            capabilities.put("supportedModifiers", Arrays.asList(
                "exact", "contains", "missing", "not"
            ));
            capabilities.put("supportedPrefixes", Arrays.asList(
                "eq", "ne", "gt", "lt", "ge", "le", "sa", "eb", "ap"
            ));
            capabilities.put("ftsEnabled", true);
            capabilities.put("timestamp", new Date().toString());
            
            return ResponseEntity.ok(capabilities);
            
        } catch (Exception e) {
            logger.error("Error getting search capabilities: {}", e.getMessage(), e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get capabilities");
            error.put("message", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }


} 