package com.couchbase.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for FHIR resource to collection mapping.
 * Loads the mapping from fhir-resource-mapping.yaml
 */
@Component
@ConfigurationProperties(prefix = "fhir.mapping")
public class FhirResourceMappingConfig {
    
    private Map<String, String> resourceToCollection = new HashMap<>();
    private Map<String, String> collectionToFtsIndex = new HashMap<>();
    
    public FhirResourceMappingConfig() {
    }
    
    public Map<String, String> getResourceToCollection() {
        return resourceToCollection;
    }
    
    public void setResourceToCollection(Map<String, String> resourceToCollection) {
        this.resourceToCollection = resourceToCollection;
    }
    
    public Map<String, String> getCollectionToFtsIndex() {
        return collectionToFtsIndex;
    }
    
    public void setCollectionToFtsIndex(Map<String, String> collectionToFtsIndex) {
        this.collectionToFtsIndex = collectionToFtsIndex;
    }
    
    /**
     * Get the collection name for a given FHIR resource type
     * @param resourceType The FHIR resource type (e.g., "Patient", "Organization")
     * @return The collection name where this resource should be stored
     */
    public String getCollectionForResource(String resourceType) {
        String collection = resourceToCollection.get(resourceType);
        if (collection == null) {
            // Use _default fallback if resource type is not explicitly mapped
            collection = resourceToCollection.get("_default");
        }
        return collection;
    }
    
    /**
     * Get the FTS index name for a given collection
     * @param collectionName The collection name
     * @return The FTS index name for this collection
     */
    public String getFtsIndexForCollection(String collectionName) {
        return collectionToFtsIndex.get(collectionName);
    }
    
    /**
     * Get the FTS index name for a given FHIR resource type
     * @param resourceType The FHIR resource type
     * @return The FTS index name for this resource type
     */
    public String getFtsIndexForResource(String resourceType) {
        String collection = getCollectionForResource(resourceType);
        if (collection != null) {
            return getFtsIndexForCollection(collection);
        }
        return null;
    }
    
    /**
     * Check if a resource type is supported
     * @param resourceType The FHIR resource type
     * @return true if the resource type is mapped to a collection
     */
    public boolean isResourceSupported(String resourceType) {
        // Resource is supported if explicitly mapped OR if _default fallback exists
        return resourceToCollection.containsKey(resourceType) || 
               resourceToCollection.containsKey("_default");
    }
    
    /**
     * Get all supported resource types
     * @return Set of all supported resource types
     */
    public java.util.Set<String> getSupportedResourceTypes() {
        return resourceToCollection.keySet();
    }
    
    /**
     * Get all collection names
     * @return Set of all collection names
     */
    public java.util.Set<String> getCollectionNames() {
        return collectionToFtsIndex.keySet();
    }
}
