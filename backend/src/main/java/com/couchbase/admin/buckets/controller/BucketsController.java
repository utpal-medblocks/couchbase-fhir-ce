package com.couchbase.admin.buckets.controller;

import com.couchbase.admin.buckets.model.BucketDetails;
import com.couchbase.admin.buckets.service.BucketsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for bucket management and metrics
 */
@RestController
@RequestMapping("/api/buckets")
@CrossOrigin(origins = "*")
public class BucketsController {
    
    private static final Logger logger = LoggerFactory.getLogger(BucketsController.class);
    
    @Autowired
    private BucketsService bucketsService;
    
    /**
     * Get all FHIR-enabled bucket names
     */
    @GetMapping("/fhir/names")
    public ResponseEntity<List<String>> getFhirBucketNames(@RequestParam String connectionName) {
        try {
            logger.info("Getting FHIR bucket names for connection: {}", connectionName);
            List<String> bucketNames = bucketsService.getFhirBucketNames(connectionName);
            return ResponseEntity.ok(bucketNames);
        } catch (Exception e) {
            logger.error("Failed to get FHIR bucket names: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get detailed metrics for all FHIR-enabled buckets
     */
    @GetMapping("/fhir/details")
    public ResponseEntity<List<BucketDetails>> getFhirBucketDetails(@RequestParam String connectionName) {
        try {
            logger.info("Getting FHIR bucket details for connection: {}", connectionName);
            List<BucketDetails> bucketDetails = bucketsService.getFhirBucketDetails(connectionName);
            return ResponseEntity.ok(bucketDetails);
        } catch (Exception e) {
            logger.error("Failed to get FHIR bucket details: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Check if a specific bucket is FHIR-enabled
     */
    @GetMapping("/fhir/check")
    public ResponseEntity<Boolean> isFhirBucket(
            @RequestParam String bucketName,
            @RequestParam String connectionName) {
        try {
            logger.info("Checking if bucket {} is FHIR-enabled for connection: {}", bucketName, connectionName);
            boolean isFhir = bucketsService.isFhirBucket(bucketName, connectionName);
            return ResponseEntity.ok(isFhir);
        } catch (Exception e) {
            logger.error("Failed to check FHIR status for bucket {}: {}", bucketName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
