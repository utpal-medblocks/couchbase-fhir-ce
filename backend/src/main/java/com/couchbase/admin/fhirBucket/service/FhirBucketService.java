package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.fhirBucket.model.*;
import com.couchbase.admin.fhirBucket.config.FhirBucketProperties;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.fts.config.FtsIndexCreator;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.manager.collection.CollectionManager;

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
    
    @Autowired
    private FtsIndexCreator ftsIndexCreator;
    
    // Store operation status
    private final Map<String, FhirConversionStatusDetail> operationStatus = new ConcurrentHashMap<>();
    
    public FhirBucketService() {
        // Configuration will be loaded via @Autowired dependency
    }
    
    /**
     * Start FHIR bucket conversion process with custom configuration
     */
    public FhirConversionResponse startConversion(String bucketName, String connectionName, FhirConversionRequest request) {
        String operationId = UUID.randomUUID().toString();
        
        // Create status tracking
        FhirConversionStatusDetail statusDetail = new FhirConversionStatusDetail(operationId, bucketName);
        operationStatus.put(operationId, statusDetail);
        
        // Start async conversion with custom config
        CompletableFuture.runAsync(() -> performConversion(operationId, bucketName, connectionName, request));
        
        return new FhirConversionResponse(
            operationId, 
            bucketName, 
            FhirConversionStatus.INITIATED, 
            "FHIR bucket conversion started"
        );
    }
    
    /**
     * Start FHIR bucket conversion process with default configuration
     */
    public FhirConversionResponse startConversion(String bucketName, String connectionName) {
        return startConversion(bucketName, connectionName, null);
    }
    
    /**
     * Get conversion status
     */
    public FhirConversionStatusDetail getConversionStatus(String operationId) {
        return operationStatus.get(operationId);
    }
    
    /**
     * Perform the actual conversion process with custom configuration
     */
    private void performConversion(String operationId, String bucketName, String connectionName, FhirConversionRequest request) {
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
            
            // Step 5: Create primary indexes
            updateStatus(status, "create_indexes", "Creating primary indexes for collections");
            createIndexes(cluster, bucketName);
            status.setCompletedSteps(5);
            
            // Step 6: Build deferred indexes
            updateStatus(status, "build_deferred_indexes", "Building deferred indexes");
            buildDeferredIndexes(cluster, bucketName);
            status.setCompletedSteps(6);
            
            // Step 7: Create FTS indexes
            updateStatus(status, "create_fts_indexes", "Creating FTS indexes for collections");
            createFtsIndexes(connectionName, bucketName);
            status.setCompletedSteps(7);
            
            // Step 8: Mark as FHIR bucket with custom configuration
            updateStatus(status, "mark_as_fhir", "Marking bucket as FHIR-enabled");
            markAsFhirBucketWithConfig(bucketName, connectionName, request);
            status.setCompletedSteps(8);
            
            // Completion
            status.setStatus(FhirConversionStatus.COMPLETED);
            status.setCurrentStepDescription("FHIR bucket conversion completed successfully");
            // logger.info("FHIR conversion completed for bucket: {}", bucketName);
            
        } catch (Exception e) {
            // logger.error("FHIR conversion failed for bucket: {}", bucketName, e);
            status.setStatus(FhirConversionStatus.FAILED);
            status.setErrorMessage(e.getMessage());
            status.setCurrentStepDescription("Conversion failed: " + e.getMessage());
        }
    }
    
    /**
     * Perform the actual conversion process with default configuration
     */
    private void performConversion(String operationId, String bucketName, String connectionName) {
        performConversion(operationId, bucketName, connectionName, null);
    }
    
    private void updateStatus(FhirConversionStatusDetail status, String stepName, String description) {
        status.setCurrentStep(stepName);
        status.setCurrentStepDescription(description);
        // logger.info("Operation {}: {}", status.getOperationId(), description);
    }
    
    private void createScope(CollectionManager manager, String scopeName) throws Exception {
        try {
            manager.createScope(scopeName);
            // logger.info("Created scope: {}", scopeName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                // logger.warn("Scope {} already exists, skipping", scopeName);
            } else {
                throw e;
            }
        }
    }
    
    private void createCollections(CollectionManager manager, String scopeName, 
                                 FhirBucketProperties.ScopeConfiguration scopeConfig) throws Exception {
        // logger.info("Creating collections for scope: {}", scopeName);
        for (FhirBucketProperties.CollectionConfiguration collection : scopeConfig.getCollections()) {
            try {
                // logger.info("Creating collection: {}.{}", scopeName, collection.getName());
                manager.createCollection(scopeName, collection.getName());
                // logger.info("Successfully created collection: {}.{}", scopeName, collection.getName());
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    // logger.warn("Collection {}.{} already exists, skipping", scopeName, collection.getName());
                } else {
                    // logger.error("Failed to create collection {}.{}: {}", scopeName, collection.getName(), e.getMessage());
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
                            // logger.error("SQL is null for index: {} in collection: {}.{}", 
                            //            index.getName(), scopeConfig.getName(), collection.getName());
                            continue; // Skip this index
                        }
                        
                        String sql = index.getSql().replace("{bucket}", bucketName);
                        cluster.query(sql, QueryOptions.queryOptions().timeout(java.time.Duration.ofMinutes(5)));
                        // logger.info("Created index: {} for collection: {}.{}", 
                        //           index.getName(), scopeConfig.getName(), collection.getName());
                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                            // logger.warn("Index {} already exists, skipping", index.getName());
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
                // logger.info("Executing build command: {}", buildCommand.getName());
                
                // Execute the query to get the BUILD INDEX statements
                String query = buildCommand.getQuery().replace("{bucket}", bucketName);
                
                try {
                    var result = cluster.query(query);
                    
                    // Process each BUILD INDEX statement - use rowsAs() for raw string results
                    for (String buildIndexSql : result.rowsAs(String.class)) {
                        // Remove quotes if present
                        buildIndexSql = buildIndexSql.replaceAll("^\"|\"$", "");
                        // logger.info("Executing BUILD INDEX: {}", buildIndexSql);
                        
                        try {
                            cluster.query(buildIndexSql);
                            // logger.info("Successfully built index");
                        } catch (Exception e) {
                            // logger.warn("Failed to build index: {} - {}", buildIndexSql, e.getMessage());
                            // Continue with other indexes even if one fails
                        }
                    }
                } catch (Exception e) {
                    // logger.error("Failed to execute build command query: {}", query, e);
                    throw e;
                }
            }
        } else {
            // logger.info("No build commands found in configuration");
        }
    }
    
    private void createFtsIndexes(String connectionName, String bucketName) throws Exception {
        try {
            logger.info("🔍 Starting FTS index creation for bucket: {}", bucketName);
            
            // Use the FtsIndexCreator to create all FTS indexes via REST API
            ftsIndexCreator.createAllFtsIndexesForBucket(connectionName, bucketName);
            
            logger.info("✅ FTS index creation completed for bucket: {}", bucketName);
            
        } catch (Exception e) {
            logger.error("❌ Failed to create FTS indexes for bucket: {}", bucketName, e);
            // Don't throw the exception - FTS indexes are optional
            // The bucket creation should continue even if FTS fails
            logger.warn("⚠️ Continuing bucket creation without FTS indexes");
        }
    }
    
    private void markAsFhirBucketWithConfig(String bucketName, String connectionName, FhirConversionRequest request) throws Exception {
        // Get connection to insert the FHIR configuration document
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
        }
        
        // Use custom configuration if provided, otherwise use defaults
        FhirBucketConfig customConfig = (request != null) ? request.getFhirConfiguration() : null;
        
        // Create profile configuration
        var profileConfig = com.couchbase.client.java.json.JsonArray.create();
        if (customConfig != null && customConfig.getProfiles() != null && !customConfig.getProfiles().isEmpty()) {
            for (FhirBucketConfig.Profile profile : customConfig.getProfiles()) {
                profileConfig.add(com.couchbase.client.java.json.JsonObject.create()
                    .put("profile", profile.getProfile() != null ? profile.getProfile() : "US Core")
                    .put("version", profile.getVersion() != null ? profile.getVersion() : "6.1.0"));
            }
        } else {
            // Default profile
            profileConfig.add(com.couchbase.client.java.json.JsonObject.create()
                .put("profile", "US Core")
                .put("version", "6.1.0"));
        }
        
        // Create validation configuration (simplified structure)
        var validationConfig = com.couchbase.client.java.json.JsonObject.create();
        if (customConfig != null && customConfig.getValidation() != null) {
            FhirBucketConfig.Validation validation = customConfig.getValidation();
            validationConfig
                .put("mode", validation.getMode() != null ? validation.getMode() : "lenient")
                .put("profile", validation.getProfile() != null ? validation.getProfile() : "none");
        } else {
            // Default validation (simplified)
            validationConfig
                .put("mode", "lenient")
                .put("profile", "none");
        }
        
        // Create logs configuration
        var logsConfig = com.couchbase.client.java.json.JsonObject.create();
        if (customConfig != null && customConfig.getLogs() != null) {
            FhirBucketConfig.Logs logs = customConfig.getLogs();
            logsConfig
                .put("enableSystem", logs.isEnableSystem())
                .put("enableCRUDAudit", logs.isEnableCRUDAudit())
                .put("enableSearchAudit", logs.isEnableSearchAudit())
                .put("rotationBy", logs.getRotationBy() != null ? logs.getRotationBy() : "size")
                .put("number", logs.getNumber() > 0 ? logs.getNumber() : 30)
                .put("s3Endpoint", logs.getS3Endpoint() != null ? logs.getS3Endpoint() : "");
        } else {
            // Default logs
            logsConfig
                .put("enableSystem", false)
                .put("enableCRUDAudit", false)
                .put("enableSearchAudit", false)
                .put("rotationBy", "size")
                .put("number", 30)
                .put("s3Endpoint", "");
        }
        
        // Create the comprehensive FHIR configuration document
        var fhirConfig = com.couchbase.client.java.json.JsonObject.create()
            .put("isFHIR", true)
            .put("createdAt", java.time.Instant.now().toString())
            .put("version", "1.0")
            .put("description", "FHIR-enabled bucket configuration")
            .put("fhirRelease", customConfig != null && customConfig.getFhirRelease() != null ? 
                 customConfig.getFhirRelease() : "Release 4")
            .put("profiles", profileConfig)
            .put("validation", validationConfig)
            .put("logs", logsConfig);
        
        // Insert the document into Admin/config collection
        String documentId = "fhir-config";
        String sql = String.format(
            "INSERT INTO `%s`.`Admin`.`config` (KEY, VALUE) VALUES ('%s', %s)",
            bucketName, documentId, fhirConfig.toString()
        );
        
        try {
            cluster.query(sql);
            // logger.info("Successfully marked bucket {} as FHIR-enabled with custom configuration", bucketName);
            
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                // logger.warn("FHIR config document already exists in bucket: {}", bucketName);
            } else {
                // logger.error("Failed to mark bucket {} as FHIR-enabled: {}", bucketName, e.getMessage());
                throw e;
            }
        }
    }
    
    private void markAsFhirBucket(String bucketName, String connectionName) throws Exception {
        markAsFhirBucketWithConfig(bucketName, connectionName, null);
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
            // logger.error("Failed to get FHIR buckets: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Check if a bucket is FHIR-enabled by looking for the configuration document
     * Uses REST API to avoid SDK retry issues
     */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        try {
            // Get connection details from connection service
            String hostname = connectionService.getHostname(connectionName);
            int port = connectionService.getPort(connectionName);
            var connectionDetails = connectionService.getConnectionDetails(connectionName);
            
            if (hostname == null || connectionDetails == null) {
                // logger.warn("Could not get connection details for REST call");
                return false;
            }
            
            // Use REST API to check if fhir-config document exists
            return checkFhirConfigViaRest(hostname, port, bucketName, connectionName,
                                        connectionDetails.getUsername(), 
                                        connectionDetails.getPassword());
            
        } catch (Exception e) {
            // logger.warn("Failed to check if bucket {} is FHIR-enabled: {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check FHIR config document via REST API using SDK's HTTP client
     */
    private boolean checkFhirConfigViaRest(String hostname, int port, String bucketName, String connectionName,
                                         String username, String password) {
        try {
            // Get the cluster connection to access the HTTP client
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                // logger.warn("No cluster connection available for REST call");
                return false;
            }
            
            // Use SDK's HTTP client
            com.couchbase.client.java.http.CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Construct the REST path for the fhir-config document
            com.couchbase.client.java.http.HttpResponse httpResponse = httpClient.get(
                com.couchbase.client.java.http.HttpTarget.manager(),
                com.couchbase.client.java.http.HttpPath.of(
                    "/pools/default/buckets/{}/scopes/Admin/collections/config/docs/fhir-config",
                    bucketName
                )
            );
            
            int statusCode = httpResponse.statusCode();
            
            // If we get a 200, the document exists (FHIR-enabled)
            if (statusCode == 200) {
                return true;
            }
            
            // 404 means document doesn't exist (not FHIR-enabled)
            if (statusCode == 404) {
                return false;
            }
            
            // Other status codes are unexpected
            // logger.warn("Unexpected status code {} when checking FHIR config for bucket {}", statusCode, bucketName);
            return false;
            
        } catch (Exception e) {
            // Log the error but don't fail the check
            // logger.debug("REST check for FHIR config failed: {}", e.getMessage());
            return false;
        }
    }
}
