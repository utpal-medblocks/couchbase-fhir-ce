package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to build FTS queries for REFERENCE parameters.
 * Converts FHIR reference parameters to FTS-compatible queries.
 */
public class ReferenceSearchHelperFTS {
    
    private static final Logger logger = LoggerFactory.getLogger(ReferenceSearchHelperFTS.class);
    
    /**
     * Build FTS query for REFERENCE parameters
     * 
     * @param fhirContext FHIR context
     * @param resourceType The resource type being searched
     * @param paramName The parameter name (e.g., "patient", "subject")
     * @param value The parameter value (e.g., "example", "Patient/123")
     * @param searchParam HAPI search parameter definition
     * @return FTS SearchQuery or null if conversion fails
     */
    public static SearchQuery buildReferenceFTSQuery(FhirContext fhirContext, String resourceType, 
                                                   String paramName, String value, RuntimeSearchParam searchParam) {
        try {
            String path = searchParam.getPath();
            logger.debug("🔍 ReferenceSearchHelperFTS: paramName={}, HAPI path={}, value={}", paramName, path, value);
            
            // Extract the field name from the path (remove resourceType prefix)
            String fieldName = paramName;
            if (path != null && path.startsWith(resourceType + ".")) {
                fieldName = path.substring(resourceType.length() + 1);
                // Remove .where(...) clause if present
                int whereIndex = fieldName.indexOf(".where(");
                if (whereIndex != -1) {
                    fieldName = fieldName.substring(0, whereIndex);
                }
                logger.debug("🔍 ReferenceSearchHelperFTS: Using field name: {}", fieldName);
            }
            
            // Build the FTS field path
            String ftsFieldPath = fieldName + ".reference";
            
            // Handle the value - if it doesn't contain "/", add the target resource type
            String searchValue = value;
            if (!value.contains("/")) {
                // Try to determine target resource type from HAPI
                if (paramName.equalsIgnoreCase("patient") || paramName.equalsIgnoreCase("subject")) {
                    searchValue = "Patient/" + value;
                } else {
                    // For other reference types, we might need to infer from context
                    // For now, use the parameter name as the resource type
                    String targetType = paramName.substring(0, 1).toUpperCase() + paramName.substring(1);
                    searchValue = targetType + "/" + value;
                }
            }
            
            logger.debug("🔍 ReferenceSearchHelperFTS: FTS field={}, search value={}", ftsFieldPath, searchValue);
            
            // Create FTS match query
            return SearchQuery.match(searchValue).field(ftsFieldPath);
            
        } catch (Exception e) {
            logger.warn("🔍 ReferenceSearchHelperFTS: Failed to build FTS query for paramName={}, value={}: {}", 
                       paramName, value, e.getMessage());
            return null;
        }
    }
}
