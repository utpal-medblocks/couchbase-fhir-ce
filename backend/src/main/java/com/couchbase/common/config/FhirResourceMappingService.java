package com.couchbase.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service layer for FHIR resource mapping operations.
 * Provides routing logic for CRUD operations and FTS searches based on
 * the loaded resource mapping configuration from fhir.yml.
 */
@Service
public class FhirResourceMappingService {
    
    @Autowired
    private FhirResourceMappingConfig mappingConfig;
    
    /**
     * Get the target collection for a FHIR resource type
     * @param resourceType The FHIR resource type (e.g., "Patient", "Organization")
     * @return Optional containing the collection name, or empty if not supported
     */
    public Optional<String> getTargetCollection(String resourceType) {
        String collection = mappingConfig.getCollectionForResource(resourceType);
        return Optional.ofNullable(collection);
    }
    
    /**
     * Get the FTS index for a FHIR resource type
     * @param resourceType The FHIR resource type
     * @return Optional containing the FTS index name, or empty if not supported
     */
    public Optional<String> getFtsIndex(String resourceType) {
        String ftsIndex = mappingConfig.getFtsIndexForResource(resourceType);
        return Optional.ofNullable(ftsIndex);
    }
    
    /**
     * Get the FTS index for a collection
     * @param collectionName The collection name
     * @return Optional containing the FTS index name, or empty if not found
     */
    public Optional<String> getFtsIndexForCollection(String collectionName) {
        String ftsIndex = mappingConfig.getFtsIndexForCollection(collectionName);
        return Optional.ofNullable(ftsIndex);
    }
    
    /**
     * Check if a resource type is supported by the mapping
     * @param resourceType The FHIR resource type
     * @return true if the resource type is mapped to a collection
     */
    public boolean isResourceSupported(String resourceType) {
        return mappingConfig.isResourceSupported(resourceType);
    }
    
    /**
     * Get the full scope.collection path for a resource type
     * @param resourceType The FHIR resource type
     * @param scopeName The scope name (defaults to "Resources")
     * @return Optional containing the full path (scope.collection), or empty if not supported
     */
    public Optional<String> getFullCollectionPath(String resourceType, String scopeName) {
        return getTargetCollection(resourceType)
                .map(collection -> scopeName + "." + collection);
    }
    
    /**
     * Get the full scope.collection path for a resource type using default scope
     * @param resourceType The FHIR resource type
     * @return Optional containing the full path (Resources.collection), or empty if not supported
     */
    public Optional<String> getFullCollectionPath(String resourceType) {
        return getFullCollectionPath(resourceType, "Resources");
    }
    
    /**
     * Get all supported resource types
     * @return Set of all supported resource types
     */
    public java.util.Set<String> getSupportedResourceTypes() {
        return mappingConfig.getSupportedResourceTypes();
    }
    
    /**
     * Get all collection names
     * @return Set of all collection names
     */
    public java.util.Set<String> getCollectionNames() {
        return mappingConfig.getCollectionNames();
    }
    
    /**
     * Validate that a resource type is supported, throwing an exception if not
     * @param resourceType The FHIR resource type to validate
     * @throws IllegalArgumentException if the resource type is not supported
     */
    public void validateResourceType(String resourceType) {
        if (!isResourceSupported(resourceType)) {
            throw new IllegalArgumentException(
                "Unsupported FHIR resource type: " + resourceType + 
                ". Supported types: " + getSupportedResourceTypes()
            );
        }
    }
    
    /**
     * Get mapping statistics for monitoring/debugging
     * @return String containing mapping statistics
     */
    public String getMappingStatistics() {
        return String.format(
            "FHIR Resource Mapping Statistics:\n" +
            "  - Total resource types: %d\n" +
            "  - Total collections: %d\n" +
            "  - Supported resource types: %s",
            mappingConfig.getResourceToCollection().size(),
            mappingConfig.getCollectionToFtsIndex().size(),
            getSupportedResourceTypes()
        );
    }
}
