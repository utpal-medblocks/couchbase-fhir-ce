package com.couchbase.admin.initialization.controller;

import com.couchbase.admin.initialization.model.InitializationStatus;
import com.couchbase.admin.initialization.service.InitializationService;
import com.couchbase.admin.fhirBucket.service.FhirBucketService;
import com.couchbase.admin.fhirBucket.model.FhirConversionResponse;
import com.couchbase.admin.fhirBucket.model.FhirConversionStatusDetail;
import com.couchbase.admin.fhirBucket.model.FhirConversionRequest;
import com.couchbase.admin.fhirBucket.model.FhirBucketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
            
            // Load FHIR configuration from config.yaml
            FhirConversionRequest request = loadFhirConfigFromYaml();
            
            // Start FHIR bucket conversion with configuration
            logger.info("✅ Starting FHIR bucket initialization for: {}", status.getBucketName());
            FhirConversionResponse response = fhirBucketService.startConversion(
                status.getBucketName(), 
                connectionName,
                request
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
    
    /**
     * Load FHIR configuration from config.yaml
     * Reads validation and logs settings from the couchbase.bucket section
     */
    private FhirConversionRequest loadFhirConfigFromYaml() {
        FhirConversionRequest request = new FhirConversionRequest();
        FhirBucketConfig config = new FhirBucketConfig();
        
        try {
            // Resolve config.yaml path (same logic as ConfigurationStartupService)
            String overrideSys = System.getProperty("fhir.config");
            String overrideEnv = System.getenv("FHIR_CONFIG_FILE");
            String chosenPath = overrideSys != null ? overrideSys : (overrideEnv != null ? overrideEnv : "../config.yaml");
            
            Path configFile = Paths.get(chosenPath);
            if (!configFile.isAbsolute()) {
                configFile = Paths.get(System.getProperty("user.dir")).resolve(configFile).normalize();
            }
            
            if (!Files.exists(configFile)) {
                logger.warn("⚠️ config.yaml not found at {}, using default FHIR configuration", configFile);
                return null;
            }
            
            // Load and parse YAML
            Yaml yaml = new Yaml();
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> yamlData = yaml.load(inputStream);
                
                if (yamlData == null) {
                    logger.warn("⚠️ config.yaml is empty, using default FHIR configuration");
                    return null;
                }
                
                // Extract couchbase.bucket section
                @SuppressWarnings("unchecked")
                Map<String, Object> couchbaseSection = (Map<String, Object>) yamlData.get("couchbase");
                if (couchbaseSection == null) {
                    logger.warn("⚠️ No 'couchbase' section in config.yaml, using defaults");
                    return null;
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> bucketSection = (Map<String, Object>) couchbaseSection.get("bucket");
                if (bucketSection == null) {
                    logger.warn("⚠️ No 'couchbase.bucket' section in config.yaml, using defaults");
                    return null;
                }
                
                // Extract FHIR release
                String fhirRelease = (String) bucketSection.get("fhirRelease");
                if (fhirRelease != null) {
                    // Convert "R4" to "Release 4"
                    config.setFhirRelease("R4".equals(fhirRelease) ? "Release 4" : fhirRelease);
                    logger.info("📋 FHIR Release: {}", config.getFhirRelease());
                }
                
                // Extract validation settings
                @SuppressWarnings("unchecked")
                Map<String, Object> validationSection = (Map<String, Object>) bucketSection.get("validation");
                if (validationSection != null) {
                    FhirBucketConfig.Validation validation = new FhirBucketConfig.Validation();
                    validation.setMode((String) validationSection.get("mode"));
                    validation.setProfile((String) validationSection.get("profile"));
                    config.setValidation(validation);
                    logger.info("📋 Validation - mode: {}, profile: {}", validation.getMode(), validation.getProfile());
                }
                
                // Extract logs settings
                @SuppressWarnings("unchecked")
                Map<String, Object> logsSection = (Map<String, Object>) bucketSection.get("logs");
                if (logsSection != null) {
                    FhirBucketConfig.Logs logs = new FhirBucketConfig.Logs();
                    logs.setEnableSystem(Boolean.TRUE.equals(logsSection.get("enableSystem")));
                    logs.setEnableCRUDAudit(Boolean.TRUE.equals(logsSection.get("enableCRUDAudit")));
                    logs.setEnableSearchAudit(Boolean.TRUE.equals(logsSection.get("enableSearchAudit")));
                    logs.setRotationBy((String) logsSection.get("rotationBy"));
                    Object numberObj = logsSection.get("number");
                    logs.setNumber(numberObj instanceof Integer ? (Integer) numberObj : 30);
                    logs.setS3Endpoint((String) logsSection.get("s3Endpoint"));
                    config.setLogs(logs);
                    logger.info("📋 Logs configuration loaded from config.yaml");
                }
                
                request.setFhirConfiguration(config);
                logger.info("✅ Successfully loaded FHIR configuration from config.yaml");
                return request;
                
            }
        } catch (Exception e) {
            logger.warn("⚠️ Failed to load FHIR configuration from config.yaml: {}, using defaults", e.getMessage());
            return null;
        }
    }
}

