package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.fhirBucket.model.*;
import com.couchbase.admin.fhirBucket.config.FhirBucketProperties;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.manager.collection.CollectionManager;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;

@Service
public class FhirBucketService {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirBucketProperties fhirProperties;
    
    // Store operation status
    private final Map<String, FhirConversionStatusDetail> operationStatus = new ConcurrentHashMap<>();
    
    public FhirBucketService() {
        // Configuration will be loaded via @Autowired dependency
    }
    
    /**
     * Start FHIR bucket conversion process
     */
    public FhirConversionResponse startConversion(String bucketName, String connectionName) {
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
            createCollections(collectionManager, "Admin", fhirProperties.getScopes().get("admin"));
            status.setCompletedSteps(3);
            
            // Step 4: Create Resource collections
            updateStatus(status, "create_resource_collections", "Creating Resource collections");
            createCollections(collectionManager, "Resources", fhirProperties.getScopes().get("resources"));
            status.setCompletedSteps(4);
            
            // Step 5: Create indexes
            updateStatus(status, "create_indexes", "Creating indexes for collections");
            createIndexes(cluster, bucketName);
            status.setCompletedSteps(5);
            
            // Step 6: Build deferred indexes
            updateStatus(status, "build_deferred_indexes", "Building deferred indexes");
            buildDeferredIndexes(cluster, bucketName);
            status.setCompletedSteps(6);
            
            // Step 7: Mark as FHIR bucket
            updateStatus(status, "mark_as_fhir", "Marking bucket as FHIR-enabled");
            markAsFhirBucket(bucketName, connectionName);
            status.setCompletedSteps(7);
            
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
                                 FhirBucketProperties.ScopeConfiguration scopeConfig) throws Exception {
        logger.info("Creating collections for scope: {}", scopeName);
        for (FhirBucketProperties.CollectionConfiguration collection : scopeConfig.getCollections()) {
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
        for (Map.Entry<String, FhirBucketProperties.ScopeConfiguration> scopeEntry : 
             fhirProperties.getScopes().entrySet()) {
            
            FhirBucketProperties.ScopeConfiguration scopeConfig = scopeEntry.getValue();
            
            for (FhirBucketProperties.CollectionConfiguration collection : scopeConfig.getCollections()) {
                for (FhirBucketProperties.IndexConfiguration index : collection.getIndexes()) {
                    try {
                        // Add null check and debug logging
                        if (index.getSql() == null) {
                            logger.error("SQL is null for index: {} in collection: {}.{}", 
                                       index.getName(), scopeConfig.getName(), collection.getName());
                            continue; // Skip this index
                        }
                        
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
    
    private void buildDeferredIndexes(Cluster cluster, String bucketName) throws Exception {
        // Get the build commands from configuration
        List<FhirBucketProperties.BuildCommand> buildCommands = fhirProperties.getBuildCommands();
        
        if (buildCommands != null && !buildCommands.isEmpty()) {
            for (FhirBucketProperties.BuildCommand buildCommand : buildCommands) {
                logger.info("Executing build command: {}", buildCommand.getName());
                
                // Execute the query to get the BUILD INDEX statements
                String query = buildCommand.getQuery().replace("{bucket}", bucketName);
                
                try {
                    var result = cluster.query(query);
                    
                    // Process each BUILD INDEX statement - use rowsAs() for raw string results
                    for (String buildIndexSql : result.rowsAs(String.class)) {
                        // Remove quotes if present
                        buildIndexSql = buildIndexSql.replaceAll("^\"|\"$", "");
                        logger.info("Executing BUILD INDEX: {}", buildIndexSql);
                        
                        try {
                            cluster.query(buildIndexSql);
                            logger.info("Successfully built index");
                        } catch (Exception e) {
                            logger.warn("Failed to build index: {} - {}", buildIndexSql, e.getMessage());
                            // Continue with other indexes even if one fails
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to execute build command query: {}", query, e);
                    throw e;
                }
            }
        } else {
            logger.info("No build commands found in configuration");
        }
    }
    
    private void markAsFhirBucket(String bucketName, String connectionName) throws Exception {
        // Get connection to insert the FHIR configuration document
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
        }
        
        // Create the FHIR configuration document using Couchbase's JsonObject
        var fhirConfig = com.couchbase.client.java.json.JsonObject.create()
            .put("isFHIR", true)
            .put("createdAt", java.time.Instant.now().toString())
            .put("version", "1.0")
            .put("description", "FHIR-enabled bucket configuration");
        
        // Insert the document into Admin/config collection
        String documentId = "fhir-config";
        String sql = String.format(
            "INSERT INTO `%s`.`Admin`.`config` (KEY, VALUE) VALUES ('%s', %s)",
            bucketName, documentId, fhirConfig.toString()
        );
        
        try {
            cluster.query(sql);
            logger.info("Successfully marked bucket {} as FHIR-enabled", bucketName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                logger.warn("FHIR config document already exists in bucket: {}", bucketName);
            } else {
                logger.error("Failed to mark bucket {} as FHIR-enabled: {}", bucketName, e.getMessage());
                throw e;
            }
        }
    }
    
    /**
     * Check if a bucket is FHIR-enabled by looking for the configuration document
     */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return false;
            }
            
            // Query for the FHIR configuration document
            String sql = String.format(
                "SELECT VALUE FROM `%s`.`Admin`.`config` WHERE META().id = 'fhir-config'",
                bucketName
            );
            
            var result = cluster.query(sql);
            var rows = result.rowsAsObject();
            
            if (!rows.isEmpty()) {
                var row = rows.get(0);
                var config = row.getObject("VALUE");
                return config != null && config.getBoolean("isFHIR");
            }
            
            return false;
        } catch (Exception e) {
            logger.warn("Failed to check if bucket {} is FHIR-enabled: {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Get all FHIR-enabled buckets
     */
    public List<String> getFhirBuckets(String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return new ArrayList<>();
            }
            
            // Query all buckets and check which ones are FHIR-enabled
            String sql = "SELECT name FROM system:buckets WHERE namespace_id = 'default'";
            var result = cluster.query(sql);
            var buckets = new ArrayList<String>();
            
            for (var row : result.rowsAsObject()) {
                String bucketName = row.getString("name");
                if (isFhirBucket(bucketName, connectionName)) {
                    buckets.add(bucketName);
                }
            }
            
            return buckets;
        } catch (Exception e) {
            logger.error("Failed to get FHIR buckets: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
