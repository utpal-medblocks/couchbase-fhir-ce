package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.common.fhir.FhirMetaHelper;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

import com.couchbase.fhir.resources.service.CollectionRoutingService;

/**
 * Service for handling FHIR POST operations (create new resources).
 * POST operations always generate server-controlled IDs and ignore any client-supplied IDs.
 * This service does NOT handle transactions - it performs simple insertions.
 */
@Service
public class PostService {
    
    private static final Logger logger = LoggerFactory.getLogger(PostService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FhirMetaHelper metaHelper;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    /**
     * Create a new FHIR resource via POST operation.
     * Always generates a server-controlled ID, ignoring any client-supplied ID.
     * 
     * @param resource The FHIR resource to create
     * @param cluster The Couchbase cluster connection
     * @param bucketName The target bucket name
     * @return The created resource with server-generated ID and metadata
     */
    public Resource createResource(Resource resource, Cluster cluster, String bucketName) {
        String resourceType = resource.getResourceType().name();
        
        // ✅ FHIR POST Semantics: Server always generates new ID (ignore any client ID)
        String serverGeneratedId = generateResourceId();
        resource.setId(serverGeneratedId);
        
        logger.info("🆔 POST {}: Generated server ID {} (ignoring any client-supplied ID)", 
                   resourceType, serverGeneratedId);
        
        // Apply proper meta with version "1" for CREATE operations
        MetaRequest metaRequest = MetaRequest.forCreate(null, "1", null);
        metaHelper.applyMeta(resource, metaRequest);
        
        // Prepare document key and JSON
        String documentKey = resourceType + "/" + serverGeneratedId;
        String resourceJson = jsonParser.encodeResourceToString(resource);
        
        // Insert into Couchbase (simple insert, no transaction handling)
        insertResource(cluster, bucketName, resourceType, documentKey, resourceJson);
        
        logger.info("✅ POST {}: Created resource with ID {}", resourceType, serverGeneratedId);
        return resource;
    }
    
    /**
     * Create a new FHIR resource within a transaction context.
     * Used by Bundle processing when the Bundle type is "transaction".
     * 
     * @param resource The FHIR resource to create
     * @param txContext The transaction context from Bundle processing
     * @param cluster The Couchbase cluster connection
     * @param bucketName The target bucket name
     * @return The created resource with server-generated ID and metadata
     */
    public Resource createResourceInTransaction(Resource resource, 
                                              com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                              Cluster cluster, 
                                              String bucketName) {
        String resourceType = resource.getResourceType().name();
        
        // ✅ FHIR POST Semantics: Server always generates new ID (ignore any client ID)
        String serverGeneratedId = generateResourceId();
        resource.setId(serverGeneratedId);
        
        logger.info("🆔 POST {} (in transaction): Generated server ID {} (ignoring any client-supplied ID)", 
                   resourceType, serverGeneratedId);
        
        // Apply proper meta with version "1" for CREATE operations
        MetaRequest metaRequest = MetaRequest.forCreate(null, "1", null);
        metaHelper.applyMeta(resource, metaRequest);
        
        // Prepare document key and JSON
        String documentKey = resourceType + "/" + serverGeneratedId;
        String resourceJson = jsonParser.encodeResourceToString(resource);
        
        // Insert into Couchbase using transaction context
        insertResourceInTransaction(txContext, cluster, bucketName, resourceType, documentKey, resourceJson);
        
        logger.info("✅ POST {} (in transaction): Created resource with ID {}", resourceType, serverGeneratedId);
        return resource;
    }
    
    /**
     * Generate a new server-controlled resource ID
     */
    private String generateResourceId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Insert resource using regular Couchbase operations (no transaction)
     */
    private void insertResource(Cluster cluster, String bucketName, String resourceType, 
                               String documentKey, String resourceJson) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Use N1QL UPSERT for consistency
            String sql = String.format(
                "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
                bucketName, DEFAULT_SCOPE, targetCollection, documentKey,
                JsonObject.fromJson(resourceJson).toString()
            );
            
            cluster.query(sql);
            logger.debug("🔧 Inserted resource: {} into collection: {}", documentKey, targetCollection);
            
        } catch (Exception e) {
            logger.error("❌ Failed to insert resource {}: {}", documentKey, e.getMessage());
            throw new RuntimeException("Failed to create resource: " + e.getMessage(), e);
        }
    }
    
    /**
     * Insert resource using transaction context (for Bundle transactions)
     */
    private void insertResourceInTransaction(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                           Cluster cluster, String bucketName, String resourceType,
                                           String documentKey, String resourceJson) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Get collection reference for transaction
            com.couchbase.client.java.Collection collection = 
                cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(targetCollection);
            
            // Insert using transaction context
            txContext.insert(collection, documentKey, JsonObject.fromJson(resourceJson));
            logger.debug("🔧 Inserted resource in transaction: {} into collection: {}", documentKey, targetCollection);
            
        } catch (Exception e) {
            logger.error("❌ Failed to insert resource {} in transaction: {}", documentKey, e.getMessage());
            throw new RuntimeException("Transaction insert failed: " + e.getMessage(), e);
        }
    }
}
