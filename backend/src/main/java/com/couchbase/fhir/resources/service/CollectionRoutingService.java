package com.couchbase.fhir.resources.service;

import com.couchbase.common.config.FhirResourceMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service for routing FHIR resource operations to the correct collections
 * based on the resource mapping configuration.
 * 
 * This service acts as a bridge between the existing DAO layer and the new
 * collection mapping system, ensuring all CRUD operations use the correct
 * target collections.
 */
@Service
public class CollectionRoutingService {
    
    private static final Logger logger = LoggerFactory.getLogger(CollectionRoutingService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private FhirResourceMappingService mappingService;
    
    /**
     * Get the target collection for a FHIR resource type
     * @param resourceType The FHIR resource type (e.g., "Patient", "Organization")
     * @return The collection name where this resource should be stored
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public String getTargetCollection(String resourceType) {
        Optional<String> collection = mappingService.getTargetCollection(resourceType);
        if (collection.isPresent()) {
            logger.debug("Routing {} to collection: {}", resourceType, collection.get());
            return collection.get();
        } else {
            String errorMsg = String.format("Unsupported FHIR resource type: %s. Supported types: %s", 
                resourceType, mappingService.getSupportedResourceTypes());
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }
    
    /**
     * Get the full scope.collection path for a resource type
     * @param resourceType The FHIR resource type
     * @return The full path (scope.collection) for this resource type
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public String getFullCollectionPath(String resourceType) {
        String collection = getTargetCollection(resourceType);
        return DEFAULT_SCOPE + "." + collection;
    }
    
    /**
     * Get the FTS index for a FHIR resource type
     * @param resourceType The FHIR resource type
     * @return The FTS index name for this resource type, or null if not found
     */
    public String getFtsIndex(String resourceType) {
        Optional<String> ftsIndex = mappingService.getFtsIndex(resourceType);
        if (ftsIndex.isPresent()) {
            // Get the bucket name from the tenant context
            String bucketName = com.couchbase.fhir.resources.config.TenantContextHolder.getTenantId();
            String fullyQualifiedIndex = bucketName + "." + DEFAULT_SCOPE + "." + ftsIndex.get();
            logger.debug("Using FTS index {} for resource type {}", fullyQualifiedIndex, resourceType);
            return fullyQualifiedIndex;
        } else {
            logger.warn("No FTS index found for resource type: {}", resourceType);
            return null;
        }
    }
    
    /**
     * Validate that a resource type is supported
     * @param resourceType The FHIR resource type to validate
     * @return true if the resource type is supported
     */
    public boolean isResourceSupported(String resourceType) {
        return mappingService.isResourceSupported(resourceType);
    }
    
    // N1QL query builders removed - using direct KV operations for CRUD
    // Only FTS operations and collection routing remain
    
    /**
     * Get routing statistics for monitoring/debugging
     * @return String containing routing statistics
     */
    public String getRoutingStatistics() {
        return String.format(
            "Collection Routing Statistics:\n" +
            "  - Total resource types: %d\n" +
            "  - Total collections: %d\n" +
            "  - Default scope: %s\n" +
            "  - Supported resource types: %s",
            mappingService.getSupportedResourceTypes().size(),
            mappingService.getCollectionNames().size(),
            DEFAULT_SCOPE,
            mappingService.getSupportedResourceTypes()
        );
    }
}
