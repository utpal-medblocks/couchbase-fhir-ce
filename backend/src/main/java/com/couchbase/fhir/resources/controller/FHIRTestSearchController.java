package com.couchbase.fhir.resources.controller;

import com.couchbase.fhir.resources.service.FHIRTestSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/fhir-search")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class FHIRTestSearchController {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestSearchController.class);

    @Autowired
    private FHIRTestSearchService searchService;

    /**
     * Search FHIR resources with parameters
     * Example: GET /api/fhir-search/Patient?name=John&gender=male
     */
    @GetMapping("/{resourceType}")
    public ResponseEntity<Map<String, Object>> searchResources(
            @PathVariable String resourceType,
            @RequestParam Map<String, String> queryParams,
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false) String bucketName) {
        
        try {
            logger.info("Searching {} resources with params: {}", resourceType, queryParams);
            
            // Remove connection/bucket params from search parameters
            queryParams.remove("connectionName");
            queryParams.remove("bucketName");
            
            List<Map<String, Object>> resources = searchService.searchResources(
                resourceType, queryParams, connectionName, bucketName);
            
            // Create FHIR Bundle response
            Map<String, Object> bundle = new HashMap<>();
            bundle.put("resourceType", "Bundle");
            bundle.put("id", resourceType.toLowerCase() + "-search-" + System.currentTimeMillis());
            bundle.put("type", "searchset");
            bundle.put("total", resources.size());
            bundle.put("timestamp", new Date().toString());
            
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> resource : resources) {
                String resourceId = (String) resource.get("id");
                Map<String, Object> entry = new HashMap<>();
                entry.put("fullUrl", String.format("http://localhost:8080/api/fhir-test/%s/%s/%s", 
                    bucketName != null ? bucketName : "fhir", resourceType, resourceId));
                entry.put("resource", resource);
                entries.add(entry);
            }
            bundle.put("entry", entries);
            
            return ResponseEntity.ok(bundle);
            
        } catch (Exception e) {
            logger.error("Error searching {} resources: {}", resourceType, e.getMessage(), e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Search failed");
            error.put("message", e.getMessage());
            error.put("resourceType", resourceType);
            error.put("timestamp", new Date().toString());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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

    /**
     * Test endpoint to verify search functionality
     */
    @GetMapping("/test/{resourceType}")
    public ResponseEntity<Map<String, Object>> testSearch(@PathVariable String resourceType) {
        try {
            Map<String, String> testParams = new HashMap<>();
            testParams.put("_text", "test");
            
            List<Map<String, Object>> resources = searchService.searchResources(
                resourceType, testParams, null, null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("resourceType", resourceType);
            result.put("totalFound", resources.size());
            result.put("searchQuery", testParams);
            result.put("timestamp", new Date().toString());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Test search failed: {}", e.getMessage(), e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("timestamp", new Date().toString());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
} 