package com.couchbase.fhir.resources.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import com.couchbase.fhir.resources.service.FHIRTestService;
import com.couchbase.fhir.search.service.FHIRTestSearchService;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/fhir-test")
public class FHIRTestController {

    @Autowired
    private FHIRTestService fhirTestService;
    
    @Autowired
    private FHIRTestSearchService fhirSearchService;

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
            return ResponseEntity.ok(fhirTestService.getCapabilities());
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
            @RequestParam Map<String, String> searchParams) {
        
        try {
            // Remove connection/bucket params from search parameters
            Map<String, String> cleanParams = new HashMap<>(searchParams);
            cleanParams.remove("connectionName");
            cleanParams.remove("bucketName");
            
            // Use advanced search service for all searches
            List<Map<String, Object>> resources = fhirSearchService.searchResources(
                resourceType, cleanParams, connectionName, bucketName);
            
            // Create FHIR Bundle response
            Map<String, Object> bundle = createSearchBundle(resourceType, bucketName, resources);
            return ResponseEntity.ok(bundle);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to search " + resourceType + ": " + e.getMessage())
            );
        }
    }

    // Dynamic Resource by ID - handles any resource type
    @GetMapping("/{bucketName}/{resourceType}/{id}")
    public ResponseEntity<?> getResourceById(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @PathVariable String id,
            @RequestParam(required = false) String connectionName) {
        
        try {
            Map<String, Object> resource = fhirTestService.getResourceById(
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

    // Dynamic Resource Creation - handles any resource type
    @PostMapping("/{bucketName}/{resourceType}")
    public ResponseEntity<?> createResource(
            @PathVariable String bucketName,
            @PathVariable String resourceType,
            @RequestParam(required = false) String connectionName,
            @RequestBody Map<String, Object> resourceData) {
        
        try {
            Map<String, Object> result = fhirTestService.createResource(
                resourceType, connectionName, bucketName, resourceData
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to create " + resourceType + ": " + e.getMessage())
            );
        }
    }
    
    // Create consistent FHIR Bundle response
    private Map<String, Object> createSearchBundle(String resourceType, String bucketName, List<Map<String, Object>> resources) {
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("resourceType", "Bundle");
        bundle.put("id", resourceType.toLowerCase() + "-search-" + System.currentTimeMillis());
        bundle.put("type", "searchset");
        bundle.put("total", resources.size());
        bundle.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
        
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            String resourceId = (String) resource.get("id");
            Map<String, Object> entry = new HashMap<>();
            entry.put("fullUrl", String.format("http://localhost:8080/api/fhir-test/%s/%s/%s", 
                bucketName, resourceType, resourceId));
            entry.put("resource", resource);
            entries.add(entry);
        }
        bundle.put("entry", entries);
        
        return bundle;
    }
}
