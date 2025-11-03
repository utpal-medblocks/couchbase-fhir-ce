package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.model.api.Include;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * N1QL-based reference extraction for _include parameters.
 * 
 * Efficiently extracts references from primary resources using server-side N1QL queries
 * instead of fetching full documents twice. Handles both simple fields and array fields.
 * 
 * Example:
 * - Simple: resource.subject.reference
 * - Array:  ARRAY loc.location.reference FOR loc IN resource.location END
 */
@Service
public class IncludeReferenceExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(IncludeReferenceExtractor.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private com.couchbase.admin.connections.service.ConnectionService connectionService;
    
    /**
     * Extract all references from primary resources for given _include parameters using N1QL.
     * Uses CTE-based approach with global de-duplication and limiting.
     * 
     * @param primaryKeys List of primary resource keys (e.g., ["Encounter/123", "Encounter/456"])
     * @param includes List of Include parameters (e.g., Encounter:subject, Encounter:participant)
     * @param primaryResourceType Primary resource type (e.g., "Encounter")
     * @param bucketName Couchbase bucket name
     * @param maxIncludeCount Maximum number of include resources to return (for bundle size limiting)
     * @return Globally deduplicated and limited list of reference keys (e.g., ["Patient/abc", "Practitioner/def"])
     */
    public List<String> extractReferences(List<String> primaryKeys, List<Include> includes, 
                                         String primaryResourceType, String bucketName, int maxIncludeCount) {
        
        if (primaryKeys == null || primaryKeys.isEmpty() || includes == null || includes.isEmpty()) {
            return Collections.emptyList();
        }
        
        logger.info("🔍 N1QL: Extracting references for {} primaries with {} includes (limit={})", 
                   primaryKeys.size(), includes.size(), maxIncludeCount);
        
        try {
            // Build N1QL query with CTE-based global de-duplication
            String n1qlQuery = buildReferenceExtractionQuery(primaryKeys, includes, primaryResourceType, bucketName, maxIncludeCount);
            
            logger.debug("🔍 N1QL Query:\n{}", n1qlQuery);
            
            // Execute N1QL query
            Cluster cluster = connectionService.getConnection("default");
            QueryResult result = cluster.query(n1qlQuery, QueryOptions.queryOptions().readonly(true));
            
            // Extract references from result (query returns single row with "references" array)
            List<String> references = new ArrayList<>();
            if (!result.rowsAsObject().isEmpty()) {
                var row = result.rowsAsObject().get(0);
                JsonArray refsArray = row.getArray("references");
                if (refsArray != null) {
                    for (int i = 0; i < refsArray.size(); i++) {
                        String ref = refsArray.getString(i);
                        if (ref != null && !ref.isEmpty()) {
                            references.add(ref);
                        }
                    }
                }
            }
            
            logger.debug("🔍 N1QL: Extracted {} unique references (globally de-duplicated)", references.size());
            
            // Limit results in Java (array slicing in N1QL had compatibility issues)
            if (references.size() > maxIncludeCount) {
                logger.info("🔍 N1QL: Limiting references from {} to {} (bundle size cap)", 
                           references.size(), maxIncludeCount);
                return references.subList(0, maxIncludeCount);
            }
            
            return references;
            
        } catch (Exception e) {
            logger.error("❌ N1QL reference extraction failed: {}", e.getMessage(), e);
            // Fall back to empty list - caller can handle gracefully
            return Collections.emptyList();
        }
    }
    
    /**
     * Build N1QL query to extract references from primary resources using CTE-based approach.
     * This provides global de-duplication across all primary documents.
     * Limiting is done in Java after retrieval.
     * 
     * Example output:
     * WITH per AS (
     *   SELECT ARRAY_DISTINCT(ARRAY_FLATTEN([
     *     [resource.subject.reference],
     *     ARRAY par.individual.reference FOR par IN resource.participant WHEN par.individual.reference IS VALUED END,
     *     ARRAY loc.location.reference FOR loc IN resource.location WHEN loc.location.reference IS VALUED END
     *   ], 1)) AS refs
     *   FROM `acme`.`Resources`.`Encounter` AS resource
     *   USE KEYS ['Encounter/key1', 'Encounter/key2']
     * )
     * SELECT ARRAY_AGG(DISTINCT r) AS references
     * FROM per
     * UNNEST per.refs AS r
     */
    private String buildReferenceExtractionQuery(List<String> primaryKeys, List<Include> includes,
                                                 String primaryResourceType, String bucketName, int maxIncludeCount) {
        
        StringBuilder query = new StringBuilder();
        
        // Start CTE
        query.append("WITH per AS (\n");
        query.append("  SELECT ARRAY_DISTINCT(ARRAY_FLATTEN([\n");
        
        List<String> fieldExpressions = new ArrayList<>();
        
        for (Include include : includes) {
            String paramName = include.getParamName();  // e.g., "subject", "participant", "location"
            
            // Get HAPI search parameter to determine field structure
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(primaryResourceType)
                    .getSearchParam(paramName);
            
            if (searchParam == null) {
                logger.warn("⚠️  Unknown include parameter: {}", paramName);
                continue;
            }
            
            String path = searchParam.getPath();  // e.g., "Encounter.subject", "Encounter.participant.individual"
            
            // Determine if this is an array field by checking HAPI metadata
            boolean isArray = isArrayField(primaryResourceType, paramName, path);
            
            // Build appropriate N1QL expression with WHEN IS VALUED filter for arrays
            String expression = buildFieldExpressionWithFilter(paramName, path, primaryResourceType, isArray);
            fieldExpressions.add(expression);
            
            logger.debug("🔍 Include '{}' → {} (array={})", paramName, expression, isArray);
        }
        
        if (fieldExpressions.isEmpty()) {
            logger.warn("⚠️  No valid include parameters found, returning empty query");
            return null;
        }
        
        // Join all field expressions with commas
        query.append("    ").append(String.join(",\n    ", fieldExpressions));
        
        query.append("\n  ], 1)) AS refs\n");
        query.append("  FROM `").append(bucketName).append("`.`Resources`.`").append(primaryResourceType).append("` AS resource\n");
        query.append("  USE KEYS [");
        
        // Add primary keys
        for (int i = 0; i < primaryKeys.size(); i++) {
            query.append("'").append(primaryKeys.get(i)).append("'");
            if (i < primaryKeys.size() - 1) {
                query.append(", ");
            }
        }
        query.append("]\n");
        query.append(")\n");
        
        // Global de-duplication (limiting done in Java after retrieval)
        query.append("SELECT ARRAY_AGG(DISTINCT r) AS references\n");
        query.append("FROM per\n");
        query.append("UNNEST per.refs AS r");
        
        return query.toString();
    }
    
    /**
     * Build N1QL field expression with WHEN IS VALUED filter for arrays.
     * 
     * Simple field: [resource.subject.reference]
     * Array field:  ARRAY item.individual.reference FOR item IN resource.participant WHEN item.individual.reference IS VALUED END
     */
    private String buildFieldExpressionWithFilter(String paramName, String path, String resourceType, boolean isArray) {
        // Extract field path from HAPI path (e.g., "Encounter.subject" → "subject")
        String fieldPath = extractFieldPath(path, resourceType);
        
        if (isArray) {
            // Array field: ARRAY item.{remainingPath}.reference FOR item IN resource.{arrayField} WHEN ... IS VALUED END
            // Example: "participant.individual" → iterate over "participant", access "individual.reference"
            String[] pathSegments = fieldPath.split("\\.");
            String arrayField = pathSegments[0];  // First segment is the array (e.g., "participant")
            
            // Remaining path after array field (e.g., "individual" for "participant.individual")
            String remainingPath = pathSegments.length > 1 ? 
                String.join(".", Arrays.copyOfRange(pathSegments, 1, pathSegments.length)) : "";
            
            String iteratorVar = paramName.substring(0, Math.min(3, paramName.length()));  // Short variable name
            
            if (remainingPath.isEmpty()) {
                // Simple array: ARRAY item.reference FOR item IN resource.location WHEN item.reference IS VALUED END
                return String.format("ARRAY %s.reference FOR %s IN resource.%s WHEN %s.reference IS VALUED END", 
                                   iteratorVar, iteratorVar, arrayField, iteratorVar);
            } else {
                // Nested array: ARRAY item.individual.reference FOR item IN resource.participant WHEN item.individual.reference IS VALUED END
                return String.format("ARRAY %s.%s.reference FOR %s IN resource.%s WHEN %s.%s.reference IS VALUED END", 
                                   iteratorVar, remainingPath, iteratorVar, arrayField, iteratorVar, remainingPath);
            }
        } else {
            // Simple field: [resource.field.reference]
            return String.format("[resource.%s.reference]", fieldPath);
        }
    }
    
    /**
     * Determine if a field is an array by checking HAPI FHIRPath expression.
     * 
     * HAPI provides paths like:
     * - Simple: "Encounter.subject" (single reference)
     * - Array: "Encounter.participant.individual" (participant is array, individual is nested reference)
     * 
     * If the path has more than 2 segments (ResourceType.field.subfield), the middle segment is typically an array.
     */
    private boolean isArrayField(String resourceType, String paramName, String path) {
        try {
            if (path == null || path.isEmpty()) {
                return false;
            }
            
            // Split path into segments
            String[] segments = path.split("\\.");
            
            // Pattern detection:
            // - "Encounter.subject" (2 segments) → simple field
            // - "Encounter.participant.individual" (3 segments) → participant is array
            // - "Encounter.location.location" (3 segments, repeated) → location is array
            
            if (segments.length > 2) {
                // Middle segment(s) indicate array structure
                logger.debug("🔍 Detected array field from path structure: {} (segments={})", path, segments.length);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.debug("Failed to determine if field is array: {}", e.getMessage());
            return false;  // Default to simple field
        }
    }
    
    /**
     * Extract field path from HAPI path.
     * 
     * Example: "Encounter.subject" → "subject"
     *          "Encounter.participant.individual" → "participant.individual"
     */
    private String extractFieldPath(String hapiPath, String resourceType) {
        if (hapiPath == null) {
            return "";
        }
        
        // Remove resource type prefix
        String prefix = resourceType + ".";
        if (hapiPath.startsWith(prefix)) {
            return hapiPath.substring(prefix.length());
        }
        
        return hapiPath;
    }
}

