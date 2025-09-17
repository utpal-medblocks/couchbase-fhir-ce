package com.couchbase.fhir.resources.validation;

import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Lightweight FHIR bucket validator that uses the single source of truth
 * 
 * This validator checks if a bucket is FHIR-enabled by leveraging the 
 * FhirBucketConfigService cache - no separate cache needed!
 */
@Component
public class FhirBucketValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketValidator.class);
    
    @Autowired
    private FhirBucketConfigService fhirBucketConfigService;
    
    /**
     * Validates if a bucket is FHIR-enabled using the single source of truth
     * @param bucketName The bucket name to validate
     * @param connectionName The connection name
     * @return true if bucket is FHIR-enabled, false otherwise
     */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        try {
            // Use the config service as single source of truth
            // If it returns a config, the bucket is FHIR-enabled
            FhirBucketConfigService.FhirBucketConfig config = 
                fhirBucketConfigService.getFhirBucketConfig(bucketName, connectionName);
            
            boolean isFhir = (config != null);
            logger.debug("🔍 FHIR bucket validation for {}:{} = {} (via config service)", 
                        connectionName, bucketName, isFhir);
            return isFhir;
            
        } catch (Exception e) {
            logger.debug("🔍 FHIR bucket validation failed for {}:{} - assuming not FHIR: {}", 
                        connectionName, bucketName, e.getMessage());
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
     * Clear cache for a specific bucket - delegates to config service
     * (kept for backward compatibility)
     */
    public void clearCache(String bucketName, String connectionName) {
        fhirBucketConfigService.clearConfigCache(bucketName, connectionName);
        logger.debug("🗑️ Delegated FHIR bucket cache clear for {}:{}", connectionName, bucketName);
    }
    
    /**
     * Clear all cache entries - delegates to config service
     * (kept for backward compatibility)
     */
    public void clearAllCache() {
        fhirBucketConfigService.clearAllConfigCache();
        logger.debug("🗑️ Delegated clear all FHIR bucket cache entries");
    }
}
