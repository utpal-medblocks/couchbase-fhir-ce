package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Bucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper service for common FHIR resource storage operations with audit metadata
 */
@Component
public class FHIRResourceStorageHelper {
    
    private static final Logger log = LoggerFactory.getLogger(FHIRResourceStorageHelper.class);
    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private FHIRAuditService auditService;
    
    @Autowired
    private IParser jsonParser;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Process and store an individual FHIR resource with audit metadata
     * @param resourceJson The JSON string of the FHIR resource
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @return Map with processing results (resourceType, resourceId, success, etc.)
     */
    public Map<String, Object> processAndStoreResource(String resourceJson, Cluster cluster, 
                                                      String bucketName, String operation) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Parse the resource as FHIR object to add audit metadata
            Resource fhirResource = (Resource) jsonParser.parseResource(resourceJson);
            String resourceType = fhirResource.getResourceType().name();
            String resourceId = fhirResource.getIdElement().getIdPart();
            
            // Generate ID if not present
            if (resourceId == null || resourceId.isEmpty()) {
                resourceId = java.util.UUID.randomUUID().toString();
                fhirResource.setId(resourceId);
            }
            
            // Add audit information to meta
            UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
            auditService.addAuditInfoToMeta(fhirResource, auditInfo, operation);
            
            // Construct document key: resourceType/id
            String documentKey = resourceType + "/" + resourceId;
            
            // Get bucket and collection - use Resources scope and resourceType as collection
            Bucket bucket = cluster.bucket(bucketName);
            Collection collection = bucket.scope(DEFAULT_SCOPE).collection(resourceType);
            
            // Convert enhanced FHIR resource back to JSON for storage
            String enhancedResourceJson = jsonParser.encodeResourceToString(fhirResource);
            com.fasterxml.jackson.databind.JsonNode enhancedJsonNode = objectMapper.readTree(enhancedResourceJson);
            
            // Upsert the enhanced resource with audit metadata as JSON object
            collection.upsert(documentKey, enhancedJsonNode);
            
            // Build successful result
            result.put("success", true);
            result.put("resourceType", resourceType);
            result.put("resourceId", resourceId);
            result.put("documentKey", documentKey);
            result.put("operation", operation);
            
            log.debug("Successfully upserted {} resource with ID: {} (with audit metadata) into scope: {}, collection: {}", 
                    resourceType, resourceId, DEFAULT_SCOPE, resourceType);
            
        } catch (Exception e) {
            log.error("Error processing individual resource: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Process and store an individual FHIR resource from parsed Resource object
     * @param fhirResource The FHIR Resource object
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @return Map with processing results
     */
    public Map<String, Object> processAndStoreResource(Resource fhirResource, Cluster cluster, 
                                                      String bucketName, String operation) {
        try {
            // Convert to JSON and use the main method
            String resourceJson = jsonParser.encodeResourceToString(fhirResource);
            return processAndStoreResource(resourceJson, cluster, bucketName, operation);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "Failed to convert resource to JSON: " + e.getMessage());
            return result;
        }
    }
}
