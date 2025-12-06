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
 * - Automatic retry: Storage operations retry once if they fail (helps with transient network/DB issues)
 * 
 * Benefits:
 * - Off-heap storage: Eliminates 171MB+ heap consumption
 * - Automatic expiry: Collection-level maxTTL, no manual cleanup jobs
 * - Simplified code: No per-document expiry specification (less overhead)
 * - Multi-tenant: Per-bucket isolation via Admin scope
 * - Scalable: Can handle thousands of concurrent pagination states
 * - Resilient: Automatic retry on transient failures
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
     * Includes retry logic: If storage fails, retries once after a brief delay.
     * 
     * @param bucketName FHIR bucket name
     * @param token Pagination token (UUID)
     * @param state PaginationState object to store
     * @throws RuntimeException if storage fails after retry
     */
    public void storePaginationState(String bucketName, String token, PaginationState state) {
        Exception lastException = null;
        
        // Try up to 2 times (initial attempt + 1 retry)
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Collection cacheCollection = getCacheCollection(bucketName);
                
                // Convert PaginationState to JSON
                Map<String, Object> stateMap = paginationStateToMap(state);
                JsonObject jsonObject = JsonObject.fromJson(objectMapper.writeValueAsString(stateMap));
                
                // Store without explicit expiry - collection-level maxTTL handles expiration
                // Simpler, less overhead than per-document expiry
                cacheCollection.upsert(token, jsonObject);
                
                // Log appropriate info based on pagination strategy
                if (state.isUseLegacyKeyList() && state.getAllDocumentKeys() != null) {
                    logger.debug("📦 Stored pagination state (LEGACY): bucket={}, token={}, keys={} (attempt={}, collection maxTTL handles expiry)", 
                                bucketName, token, state.getAllDocumentKeys().size(), attempt);
                } else {
                    logger.debug("📦 Stored pagination state (NEW): bucket={}, token={}, type={}, offset={}, pageSize={} (attempt={}, collection maxTTL handles expiry)", 
                                bucketName, token, state.getSearchType(), state.getPrimaryOffset(), state.getPrimaryPageSize(), attempt);
                }
                
                // Success - exit loop
                if (attempt > 1) {
                    logger.debug("✅ Pagination state storage succeeded on retry attempt {}", attempt);
                }
                return;
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt < 2) {
                    logger.warn("⚠️  Failed to store pagination state (attempt {}): bucket={}, token={}, error={} - retrying...", 
                               attempt, bucketName, token, e.getMessage());
                    
                    // Brief delay before retry (50ms)
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Retry sleep interrupted");
                    }
                } else {
                    logger.error("❌ Failed to store pagination state after {} attempts: bucket={}, token={}, error={}", 
                                attempt, bucketName, token, e.getMessage());
                }
            }
        }
        
        // If we got here, all attempts failed
        throw new RuntimeException("Failed to store pagination state after 2 attempts: " + lastException.getMessage(), lastException);
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
            
            // Log appropriate info based on pagination strategy
            if (state.isUseLegacyKeyList() && state.getAllDocumentKeys() != null) {
                logger.debug("📦 Retrieved pagination state (LEGACY): bucket={}, token={}, keys={}", 
                            bucketName, token, state.getAllDocumentKeys().size());
            } else {
                logger.debug("📦 Retrieved pagination state (NEW): bucket={}, token={}, type={}, offset={}, pageSize={}", 
                            bucketName, token, state.getSearchType(), state.getPrimaryOffset(), state.getPrimaryPageSize());
            }
            
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
            logger.debug("🔧 Initializing Admin.cache collection reference: bucket={}", bucketName);
            return couchbaseGateway.getCollection("default", bucketName, ADMIN_SCOPE, CACHE_COLLECTION);
        });
    }
    
    /**
     * Convert PaginationState object to Map for JSON serialization.
     */
    private Map<String, Object> paginationStateToMap(PaginationState state) {
        Map<String, Object> map = new HashMap<>();
        
        // Common fields
        map.put("searchType", state.getSearchType());
        map.put("resourceType", state.getResourceType());
        map.put("bucketName", state.getBucketName());
        map.put("baseUrl", state.getBaseUrl());
        map.put("createdAt", state.getCreatedAt().toString());
        map.put("expiresAt", state.getExpiresAt().toString());
        
        // Legacy fields (may be null in new approach)
        map.put("allDocumentKeys", state.getAllDocumentKeys());
        map.put("pageSize", state.getPageSize());
        map.put("currentOffset", state.getCurrentOffset());
        map.put("primaryResourceCount", state.getPrimaryResourceCount());
        
        // New query-based fields (may be null in legacy approach)
        map.put("primaryFtsQueriesJson", state.getPrimaryFtsQueriesJson());
        map.put("primaryOffset", state.getPrimaryOffset());
        map.put("primaryPageSize", state.getPrimaryPageSize());
        map.put("sortFieldsJson", state.getSortFieldsJson());
        map.put("maxBundleSize", state.getMaxBundleSize());
        map.put("revIncludeResourceType", state.getRevIncludeResourceType());
        map.put("revIncludeSearchParam", state.getRevIncludeSearchParam());
        map.put("includeResourceType", state.getIncludeResourceType());
        map.put("includeSearchParam", state.getIncludeSearchParam());
        map.put("includeParamsList", state.getIncludeParamsList());
        map.put("useLegacyKeyList", state.isUseLegacyKeyList());
        
        return map;
    }
    
    /**
     * Convert Map to PaginationState object after JSON deserialization.
     */
    @SuppressWarnings("unchecked")
    private PaginationState mapToPaginationState(Map<String, Object> map) {
        return PaginationState.builder()
            // Common fields
            .searchType((String) map.get("searchType"))
            .resourceType((String) map.get("resourceType"))
            .bucketName((String) map.get("bucketName"))
            .baseUrl((String) map.get("baseUrl"))
            // Legacy fields
            .allDocumentKeys((java.util.List<String>) map.get("allDocumentKeys"))
            .pageSize(getIntOrDefault(map, "pageSize", 50))
            .currentOffset(getIntOrDefault(map, "currentOffset", 0))
            .primaryResourceCount(getIntOrDefault(map, "primaryResourceCount", 0))
            // New query-based fields
            .primaryFtsQueriesJson((java.util.List<String>) map.get("primaryFtsQueriesJson"))
            .primaryOffset(getIntOrDefault(map, "primaryOffset", 0))
            .primaryPageSize(getIntOrDefault(map, "primaryPageSize", 50))
            .sortFieldsJson((java.util.List<String>) map.get("sortFieldsJson"))
            .maxBundleSize(getIntOrDefault(map, "maxBundleSize", 500))
            .revIncludeResourceType((String) map.get("revIncludeResourceType"))
            .revIncludeSearchParam((String) map.get("revIncludeSearchParam"))
            .includeResourceType((String) map.get("includeResourceType"))
            .includeSearchParam((String) map.get("includeSearchParam"))
            .includeParamsList((java.util.List<String>) map.get("includeParamsList"))
            .useLegacyKeyList(getBoolOrDefault(map, "useLegacyKeyList", false))
            .build();
    }
    
    /**
     * Helper to safely get Integer values from map with default
     */
    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return (value instanceof Number) ? ((Number) value).intValue() : defaultValue;
    }
    
    /**
     * Helper to safely get Boolean values from map with default
     */
    private boolean getBoolOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return (value instanceof Boolean) ? (Boolean) value : defaultValue;
    }
    
    /**
     * Clear all cached collection references (for testing or bucket changes).
     */
    public void clearCollectionCache() {
        cacheCollectionCache.clear();
        logger.debug("🔧 Cleared pagination cache collection references");
    }
}

