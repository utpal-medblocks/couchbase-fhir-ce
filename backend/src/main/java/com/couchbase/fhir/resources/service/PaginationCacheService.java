package com.couchbase.fhir.resources.service;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.fhir.resources.gateway.CouchbaseGateway;
import com.couchbase.fhir.resources.search.PaginationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service to manage pagination state in Couchbase Admin.cache collection (off-heap).
 * This replaces in-memory ConcurrentHashMap storage to prevent heap exhaustion and OOM errors.
 * 
 * Architecture:
 * - Each FHIR bucket has Admin.cache collection for ephemeral data
 * - Pagination states expire based on collection-level maxTTL (default 180 seconds = 3 minutes)
 * - Document key = pagination token (UUID)
 * - Couchbase collection maxTTL handles expiry automatically (no per-document expiry needed)
 * 
 * Benefits:
 * - Off-heap storage: Eliminates 171MB+ heap consumption
 * - Automatic expiry: Collection-level maxTTL, no manual cleanup jobs
 * - Simplified code: No per-document expiry specification (less overhead)
 * - Multi-tenant: Per-bucket isolation via Admin scope
 * - Scalable: Can handle thousands of concurrent pagination states
 * 
 * Configuration:
 * - Admin.cache collection must be created with maxTTL setting (e.g., 180 seconds)
 * - fhir.search.state.ttl.minutes property documents the TTL value (for reference/collection setup)
 */
@Service
public class PaginationCacheService {
    
    private static final Logger logger = LoggerFactory.getLogger(PaginationCacheService.class);
    private static final String ADMIN_SCOPE = "Admin";
    private static final String CACHE_COLLECTION = "cache";
    
    @Autowired
    private CouchbaseGateway couchbaseGateway;
    
    private final ObjectMapper objectMapper;
    
    // Cache collection references per bucket to avoid repeated lookups
    private final Map<String, Collection> cacheCollectionCache = new HashMap<>();
    
    public PaginationCacheService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Store pagination state in Couchbase Admin.cache collection.
     * 
     * TTL is managed at collection level (maxTTL setting on Admin.cache collection).
     * No per-document expiry needed - all documents auto-expire based on collection maxTTL.
     * 
     * @param bucketName FHIR bucket name
     * @param token Pagination token (UUID)
     * @param state PaginationState object to store
     * @throws RuntimeException if storage fails
     */
    public void storePaginationState(String bucketName, String token, PaginationState state) {
        try {
            Collection cacheCollection = getCacheCollection(bucketName);
            
            // Convert PaginationState to JSON
            Map<String, Object> stateMap = paginationStateToMap(state);
            JsonObject jsonObject = JsonObject.fromJson(objectMapper.writeValueAsString(stateMap));
            
            // Store without explicit expiry - collection-level maxTTL handles expiration
            // Simpler, less overhead than per-document expiry
            cacheCollection.upsert(token, jsonObject);
            
            logger.debug("📦 Stored pagination state: bucket={}, token={}, keys={} (collection maxTTL handles expiry)", 
                        bucketName, token, state.getAllDocumentKeys().size());
            
        } catch (Exception e) {
            logger.error("❌ Failed to store pagination state: bucket={}, token={}, error={}", 
                        bucketName, token, e.getMessage(), e);
            throw new RuntimeException("Failed to store pagination state: " + e.getMessage(), e);
        }
    }
    
    /**
     * Retrieve pagination state from Couchbase Admin.cache collection.
     * 
     * @param bucketName FHIR bucket name
     * @param token Pagination token (UUID)
     * @return PaginationState if found and not expired, null otherwise
     */
    public PaginationState getPaginationState(String bucketName, String token) {
        if (token == null || token.isEmpty()) {
            logger.debug("📦 Null or empty token provided");
            return null;
        }
        
        try {
            Collection cacheCollection = getCacheCollection(bucketName);
            
            // Get from Couchbase
            GetResult result = cacheCollection.get(token);
            JsonObject jsonObject = result.contentAsObject();
            
            // Convert JSON to PaginationState
            @SuppressWarnings("unchecked")
            Map<String, Object> stateMap = objectMapper.readValue(jsonObject.toString(), Map.class);
            PaginationState state = mapToPaginationState(stateMap);
            
            logger.debug("📦 Retrieved pagination state: bucket={}, token={}, keys={}", 
                        bucketName, token, state.getAllDocumentKeys().size());
            
            return state;
            
        } catch (DocumentNotFoundException e) {
            logger.debug("📦 Pagination state not found (likely expired): bucket={}, token={}", 
                        bucketName, token);
            return null;
            
        } catch (Exception e) {
            logger.error("❌ Failed to retrieve pagination state: bucket={}, token={}, error={}", 
                        bucketName, token, e.getMessage());
            return null;
        }
    }
    
    /**
     * Remove pagination state from cache (optional, as TTL handles cleanup).
     * Useful for explicit cleanup when pagination is complete.
     * 
     * @param bucketName FHIR bucket name
     * @param token Pagination token (UUID)
     */
    public void removePaginationState(String bucketName, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        
        try {
            Collection cacheCollection = getCacheCollection(bucketName);
            cacheCollection.remove(token);
            
            logger.debug("📦 Removed pagination state: bucket={}, token={}", bucketName, token);
            
        } catch (DocumentNotFoundException e) {
            logger.debug("📦 Pagination state already removed or expired: bucket={}, token={}", 
                        bucketName, token);
            
        } catch (Exception e) {
            logger.warn("⚠️ Failed to remove pagination state: bucket={}, token={}, error={}", 
                       bucketName, token, e.getMessage());
        }
    }
    
    /**
     * Get Admin.cache collection for a bucket (cached to avoid repeated lookups).
     * 
     * @param bucketName FHIR bucket name
     * @return Couchbase Collection reference
     */
    private Collection getCacheCollection(String bucketName) {
        String cacheKey = bucketName + ":" + ADMIN_SCOPE + ":" + CACHE_COLLECTION;
        
        return cacheCollectionCache.computeIfAbsent(cacheKey, key -> {
            logger.info("🔧 Initializing Admin.cache collection reference: bucket={}", bucketName);
            return couchbaseGateway.getCollection("default", bucketName, ADMIN_SCOPE, CACHE_COLLECTION);
        });
    }
    
    /**
     * Convert PaginationState object to Map for JSON serialization.
     */
    private Map<String, Object> paginationStateToMap(PaginationState state) {
        Map<String, Object> map = new HashMap<>();
        map.put("searchType", state.getSearchType());
        map.put("resourceType", state.getResourceType());
        map.put("allDocumentKeys", state.getAllDocumentKeys());
        map.put("pageSize", state.getPageSize());
        map.put("currentOffset", state.getCurrentOffset());
        map.put("bucketName", state.getBucketName());
        map.put("baseUrl", state.getBaseUrl());
        map.put("primaryResourceCount", state.getPrimaryResourceCount());
        map.put("createdAt", state.getCreatedAt().toString());
        map.put("expiresAt", state.getExpiresAt().toString());
        return map;
    }
    
    /**
     * Convert Map to PaginationState object after JSON deserialization.
     */
    @SuppressWarnings("unchecked")
    private PaginationState mapToPaginationState(Map<String, Object> map) {
        return PaginationState.builder()
            .searchType((String) map.get("searchType"))
            .resourceType((String) map.get("resourceType"))
            .allDocumentKeys((java.util.List<String>) map.get("allDocumentKeys"))
            .pageSize((Integer) map.get("pageSize"))
            .currentOffset((Integer) map.get("currentOffset"))
            .bucketName((String) map.get("bucketName"))
            .baseUrl((String) map.get("baseUrl"))
            .primaryResourceCount((Integer) map.get("primaryResourceCount"))
            .build();
    }
    
    /**
     * Clear all cached collection references (for testing or bucket changes).
     */
    public void clearCollectionCache() {
        cacheCollectionCache.clear();
        logger.info("🔧 Cleared pagination cache collection references");
    }
}

