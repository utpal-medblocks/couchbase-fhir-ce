package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.fhirBucket.model.*;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.manager.collection.CollectionManager;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Service
public class FhirBucketService {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirConfigurationLoader configurationLoader;
    
    private FhirConfiguration fhirConfig;
    
    // Store operation status
    private final Map<String, FhirConversionStatusDetail> operationStatus = new ConcurrentHashMap<>();
    
    public FhirBucketService() {
        // Configuration will be loaded via @Autowired dependency
    }
    
    /**
     * Load FHIR configuration from YAML file
     */
    private void loadFhirConfiguration() {
        if (fhirConfig == null) {
            try {
                this.fhirConfig = configurationLoader.loadConfiguration();
                logger.info("FHIR configuration loaded successfully");
                
                // Debug: Log what collections will be created
                for (Map.Entry<String, FhirConfiguration.ScopeConfiguration> scopeEntry : 
                     fhirConfig.getFhir().getScopes().entrySet()) {
                    logger.info("Scope: {} ({})", scopeEntry.getKey(), scopeEntry.getValue().getName());
                    for (FhirConfiguration.CollectionConfiguration collection : scopeEntry.getValue().getCollections()) {
                        logger.info("  Collection: {}", collection.getName());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to load FHIR configuration", e);
                throw new RuntimeException("Failed to load FHIR configuration", e);
            }
        }
    }
    
    /**
     * Start FHIR bucket conversion process
     */
    public FhirConversionResponse startConversion(String bucketName, String connectionName) {
        // Ensure configuration is loaded
        loadFhirConfiguration();
        
        String operationId = UUID.randomUUID().toString();
        
        // Create status tracking
        FhirConversionStatusDetail statusDetail = new FhirConversionStatusDetail(operationId, bucketName);
        operationStatus.put(operationId, statusDetail);
        
        // Start async conversion
        CompletableFuture.runAsync(() -> performConversion(operationId, bucketName, connectionName));
        
        return new FhirConversionResponse(
            operationId, 
            bucketName, 
            FhirConversionStatus.INITIATED, 
            "FHIR bucket conversion started"
        );
    }
    
    /**
     * Get conversion status
     */
    public FhirConversionStatusDetail getConversionStatus(String operationId) {
        return operationStatus.get(operationId);
    }
    
    /**
     * Perform the actual conversion process
     */
    private void performConversion(String operationId, String bucketName, String connectionName) {
        FhirConversionStatusDetail status = operationStatus.get(operationId);
        
        try {
            status.setStatus(FhirConversionStatus.IN_PROGRESS);
            
            // Get connection by name
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            CollectionManager collectionManager = cluster.bucket(bucketName).collections();
            
            // Step 1: Create Admin scope
            updateStatus(status, "create_admin_scope", "Creating Admin scope");
            createScope(collectionManager, "Admin");
            status.setCompletedSteps(1);
            
            // Step 2: Create Resources scope
            updateStatus(status, "create_resources_scope", "Creating Resources scope");
            createScope(collectionManager, "Resources");
            status.setCompletedSteps(2);
            
            // Step 3: Create Admin collections
            updateStatus(status, "create_admin_collections", "Creating Admin collections");
            createCollections(collectionManager, "Admin", fhirConfig.getFhir().getScopes().get("admin"));
            status.setCompletedSteps(3);
            
            // Step 4: Create Resource collections
            updateStatus(status, "create_resource_collections", "Creating Resource collections");
            createCollections(collectionManager, "Resources", fhirConfig.getFhir().getScopes().get("resources"));
            status.setCompletedSteps(4);
            
            // Step 5: Create indexes
            updateStatus(status, "create_indexes", "Creating indexes for collections");
            createIndexes(cluster, bucketName);
            status.setCompletedSteps(5);
            
            // Step 6: Mark as FHIR bucket
            updateStatus(status, "mark_as_fhir", "Marking bucket as FHIR-enabled");
            markAsFhirBucket(bucketName);
            status.setCompletedSteps(6);
            
            // Completion
            status.setStatus(FhirConversionStatus.COMPLETED);
            status.setCurrentStepDescription("FHIR bucket conversion completed successfully");
            logger.info("FHIR conversion completed for bucket: {}", bucketName);
            
        } catch (Exception e) {
            logger.error("FHIR conversion failed for bucket: {}", bucketName, e);
            status.setStatus(FhirConversionStatus.FAILED);
            status.setErrorMessage(e.getMessage());
            status.setCurrentStepDescription("Conversion failed: " + e.getMessage());
        }
    }
    
    private void updateStatus(FhirConversionStatusDetail status, String stepName, String description) {
        status.setCurrentStep(stepName);
        status.setCurrentStepDescription(description);
        logger.info("Operation {}: {}", status.getOperationId(), description);
    }
    
    private void createScope(CollectionManager manager, String scopeName) throws Exception {
        try {
            manager.createScope(scopeName);
            logger.info("Created scope: {}", scopeName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                logger.warn("Scope {} already exists, skipping", scopeName);
            } else {
                throw e;
            }
        }
    }
    
    private void createCollections(CollectionManager manager, String scopeName, 
                                 FhirConfiguration.ScopeConfiguration scopeConfig) throws Exception {
        logger.info("Creating collections for scope: {}", scopeName);
        for (FhirConfiguration.CollectionConfiguration collection : scopeConfig.getCollections()) {
            try {
                logger.info("Creating collection: {}.{}", scopeName, collection.getName());
                CollectionSpec spec = CollectionSpec.create(collection.getName(), scopeName);
                manager.createCollection(spec);
                logger.info("Successfully created collection: {}.{}", scopeName, collection.getName());
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    logger.warn("Collection {}.{} already exists, skipping", scopeName, collection.getName());
                } else {
                    logger.error("Failed to create collection {}.{}: {}", scopeName, collection.getName(), e.getMessage());
                    throw e;
                }
            }
        }
    }
    
    private void createIndexes(Cluster cluster, String bucketName) throws Exception {
        // Create indexes for both scopes
        for (Map.Entry<String, FhirConfiguration.ScopeConfiguration> scopeEntry : 
             fhirConfig.getFhir().getScopes().entrySet()) {
            
            FhirConfiguration.ScopeConfiguration scopeConfig = scopeEntry.getValue();
            
            for (FhirConfiguration.CollectionConfiguration collection : scopeConfig.getCollections()) {
                for (FhirConfiguration.IndexConfiguration index : collection.getIndexes()) {
                    try {
                        String sql = index.getSql().replace("{bucket}", bucketName);
                        cluster.query(sql, QueryOptions.queryOptions().timeout(java.time.Duration.ofMinutes(5)));
                        logger.info("Created index: {} for collection: {}.{}", 
                                  index.getName(), scopeConfig.getName(), collection.getName());
                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                            logger.warn("Index {} already exists, skipping", index.getName());
                        } else {
                            throw e;
                        }
                    }
                }
            }
        }
    }
    
    private void markAsFhirBucket(String bucketName) {
        // This could be implemented as:
        // 1. Update a configuration collection
        // 2. Set a bucket metadata
        // 3. Update application state
        // For now, we'll just log it
        logger.info("Marked bucket {} as FHIR-enabled", bucketName);
    }
}
