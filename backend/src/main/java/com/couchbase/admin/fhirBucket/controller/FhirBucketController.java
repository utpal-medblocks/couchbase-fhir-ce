package com.couchbase.admin.fhirBucket.controller;

import com.couchbase.admin.fhirBucket.model.FhirConversionRequest;
import com.couchbase.admin.fhirBucket.model.FhirConversionResponse;
import com.couchbase.admin.fhirBucket.model.FhirConversionStatusDetail;
import com.couchbase.admin.fhirBucket.service.FhirBucketService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for FHIR bucket conversion operations
 */
@RestController
@RequestMapping("/api/admin/fhir-bucket")
@CrossOrigin(origins = "*")
public class FhirBucketController {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketController.class);
    
    @Autowired
    private FhirBucketService fhirBucketService;
    
    @Autowired
    private FhirBucketConfigService fhirBucketConfigService;
    
    /**
     * Start FHIR bucket conversion
     * POST /api/admin/fhir-bucket/{bucketName}/convert
     */
    @PostMapping("/{bucketName}/convert")
    public ResponseEntity<FhirConversionResponse> convertBucket(
            @PathVariable String bucketName,
            @RequestParam String connectionName,
            @RequestBody(required = false) FhirConversionRequest request) {
        
        try {
            logger.info("Starting FHIR conversion for bucket: {} using connection: {}", bucketName, connectionName);
            
            FhirConversionResponse response = fhirBucketService.startConversion(bucketName, connectionName);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to start FHIR conversion for bucket: {}", bucketName, e);
            FhirConversionResponse errorResponse = new FhirConversionResponse(
                null, 
                bucketName, 
                com.couchbase.admin.fhirBucket.model.FhirConversionStatus.FAILED, 
                "Failed to start conversion: " + e.getMessage()
            );
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
    
    /**
     * Get conversion status
     * GET /api/admin/fhir-bucket/conversion-status/{operationId}
     */
    @GetMapping("/conversion-status/{operationId}")
    public ResponseEntity<FhirConversionStatusDetail> getConversionStatus(@PathVariable String operationId) {
        
        try {
            logger.debug("Getting conversion status for operation: {}", operationId);
            
            FhirConversionStatusDetail status = fhirBucketService.getConversionStatus(operationId);
            
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            logger.error("Failed to get conversion status for operation: {}", operationId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Health check endpoint
     * GET /api/admin/fhir-bucket/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("FHIR Bucket service is running");
    }
    
    /**
     * Check if a bucket is FHIR-enabled
     */
    @GetMapping("/{bucketName}/is-fhir")
    public ResponseEntity<Map<String, Object>> isFhirBucket(
            @PathVariable String bucketName,
            @RequestParam String connectionName) {
        try {
            boolean isFhir = fhirBucketService.isFhirBucket(bucketName, connectionName);
            Map<String, Object> response = Map.of(
                "bucketName", bucketName,
                "isFhir", isFhir
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to check if bucket {} is FHIR-enabled: {}", bucketName, e.getMessage());
            Map<String, Object> errorResponse = Map.of(
                "error", "Failed to check FHIR status",
                "message", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Get all FHIR-enabled buckets
     */
    @GetMapping("/fhir-buckets")
    public ResponseEntity<Map<String, Object>> getFhirBuckets(@RequestParam String connectionName) {
        try {
            List<String> fhirBuckets = fhirBucketService.getFhirBuckets(connectionName);
            Map<String, Object> response = Map.of(
                "fhirBuckets", fhirBuckets,
                "count", fhirBuckets.size()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get FHIR buckets: {}", e.getMessage());
            Map<String, Object> errorResponse = Map.of(
                "error", "Failed to get FHIR buckets",
                "message", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Get FHIR configuration for a bucket
     * GET /api/admin/fhir-bucket/{bucketName}/config
     */
    @GetMapping("/{bucketName}/config")
    public ResponseEntity<?> getFhirConfiguration(
            @PathVariable String bucketName,
            @RequestParam String connectionName) {
        try {
            logger.debug("Getting FHIR configuration for bucket: {} using connection: {}", bucketName, connectionName);
            
            FhirBucketConfigService.FhirBucketConfig config = fhirBucketConfigService.getFhirBucketConfig(bucketName, connectionName);
            
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok(config);
            
        } catch (Exception e) {
            logger.error("Failed to get FHIR configuration for bucket: {}", bucketName, e);
            Map<String, Object> errorResponse = Map.of(
                "error", "Failed to get FHIR configuration",
                "message", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
