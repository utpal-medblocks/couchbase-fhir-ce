package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for handling FHIR PATCH operations using JSON Patch.
 * This is a thin wrapper that applies JSON Patch operations and delegates 
 * to PutService for versioning, validation, and storage.
 */
@Service
public class PatchService {
    
    private static final Logger logger = LoggerFactory.getLogger(PatchService.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private PutService putService;
    
    @Autowired
    private com.couchbase.admin.connections.service.ConnectionService connectionService;
    
    @Autowired
    private FHIRResourceService serviceFactory;
    
    /**
     * Apply JSON Patch operations to a FHIR resource by ID.
     * 
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param resourceId Resource ID to patch
     * @param patchBody JSON Patch operations as string
     * @return MethodOutcome with updated resource
     */
    public <T extends Resource> MethodOutcome patchResource(String resourceType, String resourceId, String patchBody, Class<T> resourceClass) {
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.info("🔧 PatchService: Processing JSON Patch for {}/{}", resourceType, resourceId);
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        
        // 1. Get current resource (direct key lookup)
        FhirResourceDaoImpl<T> dao = serviceFactory.getService(resourceClass);
        T currentResource = dao.read(resourceType, resourceId, bucketName)
            .orElseThrow(() -> new ResourceNotFoundException(new IdType(resourceType, resourceId)));
        
        logger.info("🔧 PatchService: Found existing resource with version {}", 
                   currentResource.getMeta().getVersionId());
        
        // 2. Apply JSON Patch
        T patchedResource;
        try {
            // Use HAPI FHIR's JSON parser to avoid circular reference issues
            IParser fhirParser = fhirContext.newJsonParser();
            
            // Convert FHIR resource to JSON string, then to JsonNode
            String currentResourceJson = fhirParser.encodeResourceToString(currentResource);
            JsonNode currentJson = objectMapper.readTree(currentResourceJson);
            
            // Apply the JSON Patch
            JsonPatch patch = JsonPatch.fromJson(objectMapper.readTree(patchBody));
            JsonNode patchedJson = patch.apply(currentJson);
            
            // Convert back to FHIR resource using HAPI parser
            String patchedResourceJson = objectMapper.writeValueAsString(patchedJson);
            patchedResource = (T) fhirParser.parseResource(resourceClass, patchedResourceJson);
            
            // Ensure ID consistency (patch shouldn't change ID)
            patchedResource.setId(resourceId);
            
            logger.info("🔧 PatchService: Successfully applied JSON Patch operations");
            
        } catch (JsonPatchException e) {
            logger.error("❌ PatchService: Invalid JSON Patch operation: {}", e.getMessage());
            throw new InvalidRequestException("Invalid JSON Patch: " + e.getMessage());
        } catch (Exception e) {
            logger.error("❌ PatchService: Failed to parse or apply patch: {}", e.getMessage());
            throw new InvalidRequestException("Failed to process JSON Patch: " + e.getMessage());
        }
        
        // 3. Delegate to PUT service (handles versioning, validation, conflicts, meta, audit, storage)
        try {
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");
            TransactionContextImpl context = new TransactionContextImpl(cluster, bucketName);
            
            @SuppressWarnings("unchecked")
            T updatedResource = (T) putService.updateOrCreateResource(patchedResource, context);
            
            MethodOutcome outcome = new MethodOutcome();
            outcome.setResource(updatedResource);
            outcome.setCreated(false); // PATCH is always an update (resource must exist)
            outcome.setId(new IdType(resourceType, updatedResource.getIdElement().getIdPart()));
            
            String newVersionId = updatedResource.getMeta().getVersionId();
            logger.info("✅ PatchService: Successfully patched resource {}/{}, new version {}", 
                       resourceType, resourceId, newVersionId);
            
            return outcome;
            
        } catch (ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException e) {
            // ID was tombstoned - return 409
            logger.error("❌ PatchService: Resource {}/{} is tombstoned", resourceType, resourceId);
            throw e;
        } catch (Exception e) {
            logger.error("❌ PatchService: Failed to update resource {}/{}: {}", resourceType, resourceId, e.getMessage());
            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Failed to patch resource: " + e.getMessage());
        }
    }
    
    /**
     * Apply JSON Patch operations to a FHIR resource using conditional criteria.
     * Uses SearchService to resolve the resource ID first.
     * 
     * @param resourceType FHIR resource type
     * @param criteria Search criteria to identify the resource
     * @param patchBody JSON Patch operations as string
     * @param searchService SearchService for conditional resolution
     * @return MethodOutcome with updated resource
     */
    public <T extends Resource> MethodOutcome patchResourceConditional(String resourceType, java.util.Map<String, String> criteria, String patchBody, Class<T> resourceClass, SearchService searchService) {
        logger.info("🔧 PatchService: Processing conditional JSON Patch for {} with criteria: {}", resourceType, criteria);
        
        // Resolve resource using SearchService
        ResolveResult result = searchService.resolveOne(resourceType, criteria);
        
        switch (result.getStatus()) {
            case ZERO:
                logger.warn("🔧 PatchService: No matching resource found for conditional patch");
                throw new ResourceNotFoundException("No matching resource found for conditional patch");
                
            case MANY:
                logger.warn("🔧 PatchService: Multiple matches found for conditional patch - ambiguous");
                throw new ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException("Multiple matches found - conditional patch is ambiguous");
                
            case ONE:
                String resourceId = result.getResourceId();
                logger.info("🔧 PatchService: Resolved conditional patch to resource ID: {}", resourceId);
                return patchResource(resourceType, resourceId, patchBody, resourceClass);
                
            default:
                throw new InvalidRequestException("Unexpected resolve result: " + result.getStatus());
        }
    }
}
