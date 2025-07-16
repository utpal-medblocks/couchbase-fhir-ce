package com.couchbase.fhir.resources.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import com.couchbase.fhir.resources.service.FHIRTestGeneralService;
import com.couchbase.fhir.resources.service.FHIRTestCreateService;
import com.couchbase.fhir.resources.service.FHIRTestReadService;
import com.couchbase.fhir.resources.service.FHIRTestUpdateService;
import com.couchbase.fhir.resources.service.FHIRTestDeleteService;
import com.couchbase.fhir.resources.service.FHIRTestSearchService;
import com.couchbase.fhir.resources.service.FHIRBundleProcessingService;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.hl7.fhir.r4.model.Bundle;
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
            @RequestParam Map<String, String> searchParams) {
        
        try {
            // Remove connection/bucket params from search parameters
            Map<String, String> cleanParams = new HashMap<>(searchParams);
            cleanParams.remove("connectionName");
            cleanParams.remove("bucketName");
            
            // Use advanced search service for all searches
            List<Map<String, Object>> resources = searchService.searchResources(
                resourceType, cleanParams, connectionName, bucketName);
            
            // Create proper FHIR Bundle using HAPI FHIR utilities
            String baseUrl = "http://localhost:8080/api/fhir-test/" + bucketName;
            Bundle bundle = searchService.createSearchBundle(resourceType, resources, baseUrl, cleanParams);
            
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
            @RequestBody Map<String, Object> resourceData) {
        
        try {
            Map<String, Object> result = createService.createResource(
                resourceType, connectionName, bucketName, resourceData
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (RuntimeException e) {
            // If it's a validation error, return 400 Bad Request
            if (e.getMessage().contains("FHIR validation failed")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "FHIR Validation Failed");
                errorResponse.put("message", e.getMessage());
                errorResponse.put("resourceType", resourceType);
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
            @RequestBody Map<String, Object> resourceData) {
        
        try {
            Map<String, Object> result = createService.validateResourceOnly(resourceType, resourceData);
            
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
            errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // FHIR Bundle Transaction Processing - handles multiple resource types
    @PostMapping("/{bucketName}/Bundle")
    public ResponseEntity<?> processBundleTransaction(
            @PathVariable String bucketName,
            @RequestParam(required = false) String connectionName,
            @RequestBody String bundleJson) {
        
        try {
            logger.info("🔄 Processing FHIR Bundle transaction for bucket: {}", bucketName);
            
            Map<String, Object> result = bundleService.processBundleTransaction(
                bundleJson, connectionName, bucketName
            );
            
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
            
        } catch (RuntimeException e) {
            // If it's a validation error, return 400 Bad Request
            if (e.getMessage().contains("validation failed") || e.getMessage().contains("Bundle processing failed")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "FHIR Bundle Processing Failed");
                errorResponse.put("message", e.getMessage());
                errorResponse.put("resourceType", "Bundle");
                errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }
            
            // Other runtime errors
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to process Bundle: " + e.getMessage())
            );
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Failed to process Bundle: " + e.getMessage())
            );
        }
    }
    


    // Note: Now using proper HAPI FHIR Bundle creation via searchService.createSearchBundle()
}
