package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Service for handling FHIR DELETE operations with proper tombstone management.
 * DELETE operations are idempotent and use soft delete with tombstones.
 * 
 * Flow:
 * 1. Copy current resource to Versions (if exists)
 * 2. Create tombstone ONLY if resource actually existed (FHIR best practice)
 * 3. Remove from live Resources collection (always attempt for idempotency)
 * 4. Always return 204 (even if resource didn't exist)
 */
@Service
public class DeleteService {
    
    private static final Logger logger = LoggerFactory.getLogger(DeleteService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    private static final String VERSIONS_COLLECTION = "Versions";
    private static final String TOMBSTONES_COLLECTION = "Tombstones";
    
    @Autowired
    private FhirAuditService auditService;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    /**
     * Delete a FHIR resource (soft delete with tombstone).
     * Always returns success (204) even if resource doesn't exist (idempotent).
     * 
     * @param resourceType The FHIR resource type (e.g., "Patient")
     * @param resourceId The resource ID to delete
     * @param context The transaction context (standalone or nested)
     */
    public void deleteResource(String resourceType, String resourceId, TransactionContext context) {
        String documentKey = resourceType + "/" + resourceId;
        
        logger.debug("🗑️ DELETE {}: Starting soft delete", documentKey);
        
        if (context.isInTransaction()) {
            // Operate within existing Bundle transaction
            performDeleteInTransaction(resourceType, resourceId, documentKey, context);
        } else {
            // Create standalone transaction for this DELETE operation
            performDeleteWithStandaloneTransaction(resourceType, resourceId, documentKey, context);
        }

        logger.debug("✅ DELETE {}: Soft delete completed (204 No Content)", documentKey);
    }
    
    /**
     * Handle DELETE operation within existing transaction (Bundle context)
     */
    private void performDeleteInTransaction(String resourceType, String resourceId, String documentKey, TransactionContext context) {
        try {
            handleSoftDeleteInTransaction(resourceType, resourceId, documentKey, 
                                        context.getTransactionContext(), context.getCluster(), context.getBucketName());
        } catch (Exception e) {
            logger.error("❌ DELETE {} (in transaction) failed: {}", documentKey, e.getMessage());
            throw new RuntimeException("DELETE operation failed in transaction: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle DELETE operation with standalone transaction
     */
    private void performDeleteWithStandaloneTransaction(String resourceType, String resourceId, String documentKey, TransactionContext context) {
        try {
            // Create standalone transaction for this DELETE operation
            logger.debug("🔄 DELETE {}: Starting standalone transaction for {}", resourceType, documentKey);
            context.getCluster().transactions().run(txContext -> {
                logger.debug("🔄 DELETE {}: Inside transaction context", resourceType);
                handleSoftDeleteInTransaction(resourceType, resourceId, documentKey, 
                                            txContext, context.getCluster(), context.getBucketName());
                logger.debug("✅ DELETE {}: Transaction operations completed", resourceType);
            });
            logger.debug("✅ DELETE {}: Standalone transaction committed for {}", resourceType, documentKey);
        } catch (Exception e) {
            logger.error("❌ DELETE {} (standalone transaction) failed: {}", documentKey, e.getMessage());
            throw new RuntimeException("DELETE operation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Perform soft delete within transaction context:
     * 1. Copy current to Versions (if exists)
     * 2. Create tombstone in Tombstones  
     * 3. Remove from live Resources
     */
    private void handleSoftDeleteInTransaction(String resourceType, String resourceId, String documentKey,
                                             com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                             Cluster cluster, String bucketName) {
        
        // Step 1: Copy current resource to Versions (if it exists) and get version info
        String lastVersionId = copyCurrentResourceToVersions(cluster, bucketName, resourceType, documentKey);
        
        // Step 2: Only create tombstone if resource actually existed (FHIR best practice)
        if (lastVersionId != null) {
            createTombstone(txContext, cluster, bucketName, resourceType, resourceId, lastVersionId);
            logger.debug("🪦 DELETE {}: Resource deleted - archived version {}, tombstone created, live removed",
                       documentKey, lastVersionId);
        } else {
            logger.debug("🔍 DELETE {}: Resource didn't exist - no tombstone created (idempotent 204)",
                       documentKey);
        }
        
        // Step 3: Remove from live Resources collection (if it exists) - always attempt for idempotency
        removeFromLiveCollection(txContext, cluster, bucketName, resourceType, documentKey);
    }
    
    /**
     * Copy current resource to Versions collection using efficient N1QL with RETURNING
     * Same approach as PUT service to get version ID directly from the query
     * @return The version ID of the archived resource (null if resource didn't exist)
     */
    private String copyCurrentResourceToVersions(Cluster cluster, String bucketName, String resourceType, String documentKey) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Use same efficient N1QL as PUT service with RETURNING to get version ID directly
            String sql = String.format(
                "INSERT INTO `%s`.`%s`.`%s` (KEY k, VALUE v) " +
                "SELECT " +
                "    CONCAT(META(r).id, '/', IFNULL(r.meta.versionId, '1')) AS k, " +
                "    r AS v " +
                "FROM `%s`.`%s`.`%s` r " +
                "USE KEYS '%s' " +
                "RETURNING RAW %s.meta.versionId",
                bucketName, DEFAULT_SCOPE, VERSIONS_COLLECTION,  // INSERT INTO Versions
                bucketName, DEFAULT_SCOPE, targetCollection,     // FROM TargetCollection
                documentKey,                                     // USE KEYS 'Patient/04d74bb4-61a9-45f5-8912-3d30d8029fa7'
                VERSIONS_COLLECTION                              // RETURNING RAW Versions.meta.versionId
            );
            
            logger.debug("🔄 Copying current resource to Versions with RETURNING for delete: {}", sql);
            QueryResult result = cluster.query(sql);
            
            // Get RETURNING results from SDK - this gives us the version ID that was copied
            List<String> rows = result.rowsAs(String.class);
            if (!rows.isEmpty()) {
                String versionId = rows.get(0);
                logger.debug("📂 Copied resource to Versions for delete, version: {}", versionId);
                return versionId;
            } else {
                logger.debug("🔍 Resource {} doesn't exist - no version to archive", documentKey);
                return null;
            }
            
        } catch (Exception e) {
            logger.debug("Failed to copy resource {} to Versions: {}", documentKey, e.getMessage());
            return null; // Continue with delete even if archiving fails
        }
    }
    
    /**
     * Create tombstone in Tombstones collection
     */
    private void createTombstone(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                               Cluster cluster, String bucketName, String resourceType, String resourceId, String lastVersionId) {
        try {
            // Get user info for audit
            UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
            String deletedBy = "user:" + (auditInfo != null && auditInfo.getUserId() != null ? auditInfo.getUserId() : "anonymous");
            
            // Create tombstone document
            JsonObject tombstone = JsonObject.create()
                .put("resourceType", resourceType)
                .put("id", resourceId)
                .put("deletedAt", Instant.now().toString())
                .put("lastVersionId", lastVersionId != null ? lastVersionId : "none")
                .put("deletedBy", deletedBy)
                .put("reason", "user-request")
                .put("restorable", true);
            
            // Get Tombstones collection reference
            com.couchbase.client.java.Collection tombstonesCollection = 
                cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(TOMBSTONES_COLLECTION);
            
            // UPSERT tombstone (idempotent - OK if already exists)
            String tombstoneKey = resourceType + "/" + resourceId;
            
            try {
                // Try to replace first (if tombstone already exists)
                var existingTombstone = txContext.get(tombstonesCollection, tombstoneKey);
                txContext.replace(existingTombstone, tombstone);
                logger.debug("🪦 Updated existing tombstone: {}", tombstoneKey);
            } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                // Tombstone doesn't exist, create new one
                txContext.insert(tombstonesCollection, tombstoneKey, tombstone);
                logger.debug("🪦 Created new tombstone: {}", tombstoneKey);
            }
            
        } catch (Exception e) {
            logger.error("❌ Failed to create tombstone for {}/{}: {}", resourceType, resourceId, e.getMessage());
            throw new RuntimeException("Failed to create tombstone: " + e.getMessage(), e);
        }
    }
    
    /**
     * Remove resource from live Resources collection
     */
    private void removeFromLiveCollection(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                        Cluster cluster, String bucketName, String resourceType, String documentKey) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Get live collection reference
            com.couchbase.client.java.Collection liveCollection = 
                cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(targetCollection);
            
            try {
                // Try to remove the document (idempotent - OK if doesn't exist)
                var existingDoc = txContext.get(liveCollection, documentKey);
                txContext.remove(existingDoc);
                logger.debug("🗑️ Removed resource from live collection: {}", documentKey);
            } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                // Document doesn't exist in live collection - that's OK (idempotent)
                logger.debug("🔍 Resource {} not found in live collection (already deleted or never existed)", documentKey);
            }
            
        } catch (Exception e) {
            logger.error("❌ Failed to remove resource {} from live collection: {}", documentKey, e.getMessage());
            throw new RuntimeException("Failed to remove from live collection: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if a resource ID is tombstoned (deleted)
     * Used by other services to prevent reuse of deleted IDs
     */
    public boolean isTombstoned(String resourceType, String resourceId, Cluster cluster, String bucketName) {
        try {
            String tombstoneKey = resourceType + "/" + resourceId;
            String sql = String.format(
                "SELECT COUNT(*) AS count FROM `%s`.`%s`.`%s` USE KEYS '%s'",
                bucketName, DEFAULT_SCOPE, TOMBSTONES_COLLECTION, tombstoneKey
            );
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAsObject();
            
            if (!rows.isEmpty()) {
                int count = rows.get(0).getInt("count");
                return count > 0;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.debug("Failed to check tombstone for {}/{}: {}", resourceType, resourceId, e.getMessage());
            return false; // Assume not tombstoned if check fails
        }
    }
}
