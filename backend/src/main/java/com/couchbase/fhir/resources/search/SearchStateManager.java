package com.couchbase.fhir.resources.search;

import com.couchbase.fhir.resources.service.PaginationCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Manages pagination state for FHIR search operations.
 * 
 * All pagination states are stored OFF-HEAP in Couchbase Admin.cache collection
 * to prevent OOM errors that occurred with in-memory storage.
 * 
 * Previous (Legacy): 171MB heap consumed by ConcurrentHashMap with ~1,600 pagination states → OOM
 * Current (Couchbase): <1MB heap, states stored in Couchbase with automatic TTL expiry → No OOM
 * 
 * Pagination Strategy:
 * - Write-once, read-many: Document created once, never updated
 * - Offset tracking: URL parameters (_offset, _count), not stored in document
 * - TTL management: Collection-level maxTTL (180s), honored from creation time
 * - Immutable state: No document updates = no TTL resets, fewer write operations
 * 
 * Supported search types: regular, _revinclude, _include, chain, $everything
 * All use the same PaginationState structure with Couchbase-backed storage.
 */
@Service
public class SearchStateManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchStateManager.class);
    
    @Autowired
    private PaginationCacheService paginationCacheService;
    
    
    /**
     * Store pagination state and return token (new off-heap strategy using Couchbase).
     * 
     * REFACTORED: Now stores in Couchbase Admin.cache instead of in-memory ConcurrentHashMap.
     * This eliminates heap pressure and prevents OOM errors.
     * 
     * @param paginationState The pagination state to store
     * @return Pagination token (UUID) for retrieving the state
     */
    public String storePaginationState(PaginationState paginationState) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String bucketName = paginationState.getBucketName();
        
        // Store in Couchbase Admin.cache (off-heap) with automatic TTL
        paginationCacheService.storePaginationState(bucketName, token, paginationState);
        
        logger.debug("Stored pagination state with token: {} (off-heap, collection maxTTL handles expiry)", 
                    token);
        return token;
    }
    
    /**
     * Retrieve pagination state by token (from Couchbase Admin.cache).
     * 
     * REFACTORED: Now retrieves from Couchbase Admin.cache instead of in-memory ConcurrentHashMap.
     * 
     * @param token The pagination token (UUID)
     * @param bucketName The FHIR bucket name
     * @return PaginationState if found and not expired, null otherwise (returns null for 410 Gone)
     */
    public PaginationState getPaginationState(String token, String bucketName) {
        if (token == null || token.isEmpty()) {
            logger.debug("Pagination state: null or empty token");
            return null;
        }
        
        // Retrieve from Couchbase Admin.cache (off-heap)
        PaginationState state = paginationCacheService.getPaginationState(bucketName, token);
        
        if (state == null) {
            logger.debug("Pagination state not found or expired: token={}, bucket={}", token, bucketName);
            return null;
        }
        
        // Check if expired (defensive check, Couchbase TTL should handle this)
        if (state.isExpired()) {
            logger.debug("Pagination state expired: token={}, bucket={}", token, bucketName);
            paginationCacheService.removePaginationState(bucketName, token);
            return null;
        }
        
        logger.debug("Retrieved pagination state: token={}, bucket={}, keys={}", 
                    token, bucketName, state.getAllDocumentKeys().size());
        return state;
    }
    
    /**
     * Remove pagination state from cache (optional cleanup).
     * 
     * REFACTORED: Now removes from Couchbase Admin.cache instead of in-memory ConcurrentHashMap.
     * Note: Couchbase TTL handles automatic cleanup, this is for explicit cleanup.
     * 
     * @param token The pagination token
     * @param bucketName The FHIR bucket name
     */
    public void removePaginationState(String token, String bucketName) {
        if (token != null && bucketName != null) {
            paginationCacheService.removePaginationState(bucketName, token);
            logger.debug("Removed pagination state: token={}, bucket={}", token, bucketName);
        }
    }
    
    
    
}
