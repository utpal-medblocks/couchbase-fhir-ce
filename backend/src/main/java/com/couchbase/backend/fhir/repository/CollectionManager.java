package com.couchbase.backend.fhir.repository;

import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class CollectionManager {
    
    private final Set<String> highCardinalityResources = Set.of(
        "Patient", "Observation", "Encounter"
    );
    
    private final Set<String> indexedResources = Set.of(
        "Condition", "Procedure", "MedicationRequest"
    );

    public String getCollectionForResource(String resourceType) {
        if (highCardinalityResources.contains(resourceType)) {
            return resourceType.toLowerCase();
        }
        if (indexedResources.contains(resourceType)) {
            return "indexed_resources";
        }
        return "other_resources";
    }

    public boolean hasComplexIndexes(String resourceType) {
        return highCardinalityResources.contains(resourceType) || 
               indexedResources.contains(resourceType);
    }
} 