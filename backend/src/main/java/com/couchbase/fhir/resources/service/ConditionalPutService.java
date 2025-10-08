package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Service for handling conditional PUT operations (upsert logic).
 * 
 * Flow:
 * 1. Search for existing resources matching criteria (LIMIT 2)
 * 2. If 0 matches → delegate to PostService (create new)
 * 3. If 1 match → inject ID into resource body, delegate to PutService (update existing)
 * 4. If 2+ matches → return 412 Precondition Failed
 */
@Service
public class ConditionalPutService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConditionalPutService.class);
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private SearchService searchService;
    
    @Autowired
    private PostService postService;
    
    @Autowired
    private PutService putService;
    
    /**
     * Perform conditional PUT operation (upsert)
     * 
     * @param resource The FHIR resource to create or update
     * @param searchCriteria Search parameters to find existing resource
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @return MethodOutcome with created/updated resource
     */
    public <T extends Resource> MethodOutcome conditionalPut(T resource, Map<String, List<String>> searchCriteria, String resourceType) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.info("🔄 ConditionalPutService: Processing conditional PUT for {}", resourceType);
        logger.debug("🔍 ConditionalPutService: Search criteria: {}", searchCriteria);
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        // Use SearchService to resolve the condition
        ResolveResult resolveResult = searchService.resolveConditional(resourceType, searchCriteria);
        
        switch (resolveResult.getStatus()) {
            case ZERO:
                // No matches - create new resource via PostService
                logger.info("✅ ConditionalPutService: No existing {} found, creating new", resourceType);
                
                // Clear any ID from the resource body (PostService will generate new ID)
                resource.setId((String) null);
                
                // Delegate to PostService
                return delegateToPostService(resource, bucketName);
                
            case ONE:
                // Single match - update existing resource via PutService
                String existingId = resolveResult.getResourceId();
                logger.info("✅ ConditionalPutService: Found existing {}: {}, updating", resourceType, existingId);
                
                // Inject the found ID into the resource body (ignore any client-provided ID)
                resource.setId(existingId);
                
                // Delegate to PutService
                return delegateToPutService(resource, existingId, bucketName);
                
            case MANY:
                // Multiple matches - return 412 Precondition Failed
                logger.warn("❌ ConditionalPutService: Multiple {} resources found matching criteria", resourceType);
                throw new PreconditionFailedException(
                    "Multiple " + resourceType + " resources found matching the specified criteria. " +
                    "Conditional PUT requires zero or one match.");
                
            default:
                throw new InternalErrorException("Unexpected resolve result: " + resolveResult.getStatus());
        }
    }
    
    /**
     * Delegate to PostService for resource creation
     */
    private <T extends Resource> MethodOutcome delegateToPostService(T resource, String bucketName) throws IOException {
        logger.info("🆕 ConditionalPutService: Delegating to PostService for creation");
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            
            @SuppressWarnings("unchecked")
            T createdResource = (T) postService.createResource(resource, cluster, bucketName);
            
            MethodOutcome outcome = new MethodOutcome();
            outcome.setCreated(true);
            outcome.setResource(createdResource);
            outcome.setId(createdResource.getIdElement());
            
            logger.info("✅ ConditionalPutService: Created new resource with ID {}", createdResource.getIdElement().getIdPart());
            return outcome;
            
        } catch (Exception e) {
            logger.error("❌ ConditionalPutService: Failed to create resource: {}", e.getMessage());
            throw new InternalErrorException("Failed to create resource: " + e.getMessage());
        }
    }
    
    /**
     * Delegate to PutService for resource update
     */
    private <T extends Resource> MethodOutcome delegateToPutService(T resource, String resourceId, String bucketName) throws IOException {
        logger.info("🔄 ConditionalPutService: Delegating to PutService for update of ID {}", resourceId);
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            TransactionContextImpl context = new TransactionContextImpl(cluster, bucketName);
            
            @SuppressWarnings("unchecked")
            T updatedResource = (T) putService.updateOrCreateResource(resource, context);
            
            MethodOutcome outcome = new MethodOutcome();
            outcome.setResource(updatedResource);
            outcome.setId(updatedResource.getIdElement());
            
            // Determine if this was actually a create or update based on version
            String versionId = updatedResource.getMeta().getVersionId();
            if ("1".equals(versionId)) {
                outcome.setCreated(true);
                logger.info("✅ ConditionalPutService: PutService created new resource with ID {}", resourceId);
            } else {
                outcome.setCreated(false);
                logger.info("✅ ConditionalPutService: PutService updated existing resource ID {}, version {}", resourceId, versionId);
            }
            
            return outcome;
            
        } catch (Exception e) {
            logger.error("❌ ConditionalPutService: Failed to update resource {}: {}", resourceId, e.getMessage());
            throw new InternalErrorException("Failed to update resource: " + e.getMessage());
        }
    }
}
