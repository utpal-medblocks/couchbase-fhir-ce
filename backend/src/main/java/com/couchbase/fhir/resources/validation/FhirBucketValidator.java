package com.couchbase.fhir.resources.validation;

import com.couchbase.admin.fhirBucket.service.FhirBucketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight FHIR bucket validator with caching to minimize performance impact
 * 
 * This validator checks if a bucket is FHIR-enabled before allowing FHIR operations.
 * Uses caching to avoid repeated REST API calls for the same bucket.
 */
@Component
public class FhirBucketValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketValidator.class);
    
    @Autowired
    private FhirBucketService fhirBucketService;
    
    // Cache for bucket validation results to minimize performance impact
    private final ConcurrentHashMap<String, CacheEntry> bucketCache = new ConcurrentHashMap<>();
    
    // Cache TTL in milliseconds (5 minutes)
    private static final long CACHE_TTL = TimeUnit.MINUTES.toMillis(5);
    
    /**
     * Validates if a bucket is FHIR-enabled
     * @param bucketName The bucket name to validate
     * @param connectionName The connection name
     * @return true if bucket is FHIR-enabled, false otherwise
     */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        String cacheKey = connectionName + ":" + bucketName;
        
        // Check cache first
        CacheEntry cached = bucketCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            logger.debug("🔍 Using cached FHIR bucket validation for {}: {}", cacheKey, cached.isFhir);
            return cached.isFhir;
        }
        
        // Cache miss or expired - validate via service
        try {
            boolean isFhir = fhirBucketService.isFhirBucket(bucketName, connectionName);
            
            // Cache the result
            bucketCache.put(cacheKey, new CacheEntry(isFhir, System.currentTimeMillis()));
            
            logger.debug("🔍 Validated FHIR bucket {}: {} (cached for {} min)", 
                        cacheKey, isFhir, CACHE_TTL / 60000);
            
            return isFhir;
            
        } catch (Exception e) {
            logger.warn("❌ Failed to validate FHIR bucket {}: {}", cacheKey, e.getMessage());
            
            // On error, cache negative result for shorter time to avoid repeated failures
            bucketCache.put(cacheKey, new CacheEntry(false, System.currentTimeMillis()));
            return false;
        }
    }
    
    /**
     * Validates FHIR bucket and throws exception if not valid
     */
    public void validateFhirBucketOrThrow(String bucketName, String connectionName) {
        if (!isFhirBucket(bucketName, connectionName)) {
            throw new FhirBucketValidationException(
                "Bucket '" + bucketName + "' is not FHIR-enabled. " +
                "Please convert it to a FHIR bucket first using the admin interface."
            );
        }
    }
    
    /**
     * Clear cache for a specific bucket (useful when bucket status changes)
     */
    public void clearCache(String bucketName, String connectionName) {
        String cacheKey = connectionName + ":" + bucketName;
        bucketCache.remove(cacheKey);
        logger.debug("🗑️ Cleared FHIR bucket cache for {}", cacheKey);
    }
    
    /**
     * Clear all cache entries
     */
    public void clearAllCache() {
        bucketCache.clear();
        logger.debug("🗑️ Cleared all FHIR bucket cache entries");
    }
    
    /**
     * Cache entry with expiration
     */
    private static class CacheEntry {
        final boolean isFhir;
        final long timestamp;
        
        CacheEntry(boolean isFhir, long timestamp) {
            this.isFhir = isFhir;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }
}
