package com.couchbase.admin.initialization.controller;

import com.couchbase.admin.initialization.model.InitializationStatus;
import com.couchbase.admin.initialization.service.InitializationService;
import com.couchbase.admin.fhirBucket.service.FhirBucketService;
import com.couchbase.admin.fhirBucket.model.FhirConversionResponse;
import com.couchbase.admin.fhirBucket.model.FhirConversionStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for FHIR system initialization status and operations
 */
@RestController
@RequestMapping("/api/admin/initialization")
public class InitializationController {
    
    private static final Logger logger = LoggerFactory.getLogger(InitializationController.class);
    
    @Autowired
    private InitializationService initializationService;
    
    @Autowired
    private FhirBucketService fhirBucketService;
    
    /**
     * Get the current initialization status of the FHIR system
     * 
     * @return InitializationStatus with detailed information about system state
     */
    @GetMapping("/status")
    public ResponseEntity<InitializationStatus> getStatus(
            @RequestParam(defaultValue = "default") String connectionName) {
        
        logger.info("📊 Checking initialization status for connection: {}", connectionName);
        
        try {
            InitializationStatus status = initializationService.checkStatus(connectionName);
            logger.info("📊 Status: {} - {}", status.getStatus(), status.getMessage());
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("❌ Failed to check initialization status: {}", e.getMessage());
            
            // Return error status
            InitializationStatus errorStatus = new InitializationStatus();
            errorStatus.setStatus(InitializationStatus.Status.NOT_CONNECTED);
            errorStatus.setMessage("Failed to check status: " + e.getMessage());
            errorStatus.setHasConnection(false);
            errorStatus.setBucketExists(false);
            errorStatus.setFhirInitialized(false);
            
            return ResponseEntity.ok(errorStatus);
        }
    }
    
    /**
     * Initialize the FHIR bucket (single-tenant mode)
     * Creates scopes, collections, FTS indexes, and GSI indexes
     * 
     * @return FhirConversionResponse with operation ID to track progress
     */
    @PostMapping("/initialize")
    public ResponseEntity<FhirConversionResponse> initializeFhirBucket(
            @RequestParam(defaultValue = "default") String connectionName) {
        
        logger.info("🚀 Starting FHIR bucket initialization for connection: {}", connectionName);
        
        try {
            // Check current status first
            InitializationStatus status = initializationService.checkStatus(connectionName);
            
            // Validate that we're in the right state to initialize
            if (status.getStatus() == InitializationStatus.Status.NOT_CONNECTED) {
                logger.error("❌ Cannot initialize: No connection to Couchbase");
                return ResponseEntity.badRequest().body(
                    new FhirConversionResponse(null, null, null, 
                        "Cannot initialize: No connection to Couchbase. Please check config.yaml and restart.")
                );
            }
            
            if (!status.isBucketExists()) {
                logger.error("❌ Cannot initialize: Bucket '{}' does not exist", status.getBucketName());
                return ResponseEntity.badRequest().body(
                    new FhirConversionResponse(null, status.getBucketName(), null,
                        "Cannot initialize: Bucket '" + status.getBucketName() + "' does not exist. Please create it first.")
                );
            }
            
            if (status.isFhirInitialized()) {
                logger.warn("⚠️ Bucket '{}' is already FHIR-initialized", status.getBucketName());
                return ResponseEntity.ok(
                    new FhirConversionResponse(null, status.getBucketName(), null,
                        "Bucket '" + status.getBucketName() + "' is already FHIR-initialized")
                );
            }
            
            // Start FHIR bucket conversion
            logger.info("✅ Starting FHIR bucket initialization for: {}", status.getBucketName());
            FhirConversionResponse response = fhirBucketService.startConversion(
                status.getBucketName(), 
                connectionName
            );
            
            logger.info("🎯 Initialization started with operation ID: {}", response.getOperationId());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize FHIR bucket: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                new FhirConversionResponse(null, null, null,
                    "Initialization failed: " + e.getMessage())
            );
        }
    }
    
    /**
     * Get the status of an initialization operation
     * 
     * @param operationId The operation ID returned from /initialize
     * @return Status details of the initialization
     */
    @GetMapping("/operation/{operationId}")
    public ResponseEntity<FhirConversionStatusDetail> getOperationStatus(
            @PathVariable String operationId) {
        
        logger.debug("📊 Checking operation status: {}", operationId);
        
        try {
            FhirConversionStatusDetail status = fhirBucketService.getConversionStatus(operationId);
            
            if (status == null) {
                logger.warn("⚠️ Operation not found: {}", operationId);
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            logger.error("❌ Failed to get operation status: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}

