package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.MetaRequest;
import com.couchbase.common.fhir.FhirMetaHelper;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.PatchTypeEnum;

import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Generic FHIR resource provider for HAPI FHIR that enables CRUD operations and search capabilities
 * for any FHIR resource type backed by a Couchbase data store.
 *
 * <p>This class dynamically handles requests for FHIR resources using the generic type {@code T}
 * and delegates persistence logic to the associated {@link FhirResourceDaoImpl}. It integrates
 * validation using the HAPI FHIR validation API and ensures the resource conforms to US Core profiles
 * when applicable.</p>
 *
 * @param <T> A FHIR resource type extending {@link Resource}
 */

public class FhirCouchbaseResourceProvider <T extends Resource> implements IResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(FhirCouchbaseResourceProvider.class);
    private final Class<T> resourceClass;
    private final FhirResourceDaoImpl<T> dao;
    private final FhirContext fhirContext;
    private final FhirBucketValidator bucketValidator;
    private final FhirBucketConfigService configService;
    private final FhirValidator strictValidator; // Primary US Core validator
    private final FhirValidator lenientValidator; // Basic validator
    private final com.couchbase.admin.connections.service.ConnectionService connectionService;
    private final com.couchbase.fhir.resources.service.PutService putService;
    private final com.couchbase.fhir.resources.service.DeleteService deleteService;
    private final FhirMetaHelper metaHelper;
    private final com.couchbase.fhir.resources.service.SearchService searchService;
    private final com.couchbase.fhir.resources.service.PatchService patchService;


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext, FhirSearchParameterPreprocessor searchPreprocessor, FhirBucketValidator bucketValidator, FhirBucketConfigService configService, FhirValidator strictValidator, FhirValidator lenientValidator, com.couchbase.admin.connections.service.ConnectionService connectionService, com.couchbase.fhir.resources.service.PutService putService, com.couchbase.fhir.resources.service.DeleteService deleteService, FhirMetaHelper metaHelper, com.couchbase.fhir.resources.service.SearchService searchService, com.couchbase.fhir.resources.service.PatchService patchService) {
        this.resourceClass = resourceClass;
        this.dao = dao;
        this.fhirContext = fhirContext;
        // searchPreprocessor is now handled by SearchService
        this.bucketValidator = bucketValidator;
        this.configService = configService;
        this.strictValidator = strictValidator;
        this.lenientValidator = lenientValidator;
        this.connectionService = connectionService;
        this.putService = putService;
        this.deleteService = deleteService;
        this.metaHelper = metaHelper;
        // objectMapper is now handled by PatchService
        this.searchService = searchService;
        this.patchService = patchService;
    }

    @Read
    public T read(@IdParam IdType theId) {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        return dao.read(resourceClass.getSimpleName(), theId.getIdPart() , bucketName).orElseThrow(() ->
                new ResourceNotFoundException(theId));
    }

    @Create
    public MethodOutcome create(@ResourceParam T resource) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        if (resource.getIdElement().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }

        // Get bucket-specific validation configuration first
        FhirBucketConfigService.FhirBucketConfig bucketConfig = configService.getFhirBucketConfig(bucketName);

        // Apply proper meta with version "1" for CREATE operations
        List<String> profiles = null;
        if (resource instanceof DomainResource && bucketConfig.isEnforceUSCore()) {
            String profileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-" + 
                                resource.getClass().getSimpleName().toLowerCase();
            profiles = List.of(profileUrl);
        }
        
        MetaRequest metaRequest = MetaRequest.forCreate(null, "1", profiles);
        metaHelper.applyMeta(resource, metaRequest);
        
        if (profiles != null && !profiles.isEmpty()) {
            logger.info("🔍 ResourceProvider: Added US Core profile: {}", profiles.get(0));
        }
        
        logger.info("🔍 ResourceProvider: Processing {} for bucket: {}", resourceClass.getSimpleName(), bucketName);
        logger.info("🔍 ResourceProvider: Bucket config - strict: {}, enforceUSCore: {}, validationDisabled: {}", 
            bucketConfig.isStrictValidation(), bucketConfig.isEnforceUSCore(), bucketConfig.isValidationDisabled());
        
        // Perform validation based on bucket configuration
        if (!bucketConfig.isValidationDisabled()) {
            // Choose validator based on bucket configuration
            FhirValidator validator;
            String validationMode;
            
            if (bucketConfig.isStrictValidation() || bucketConfig.isEnforceUSCore()) {
                validator = strictValidator;
                validationMode = "strict (US Core enforced)";
            } else {
                validator = lenientValidator;
                validationMode = "lenient (basic FHIR R4)";
            }
            
            logger.info("🔍 ResourceProvider: Using {} validation for bucket: {}", validationMode, bucketName);
            logger.info("🔍 ResourceProvider: About to validate resource with validator: {}", validator.getClass().getSimpleName());
            
            ValidationResult result = validator.validateWithResult(resource);
            
            logger.info("🔍 ResourceProvider: Validation result - isSuccessful: {}, messageCount: {}", 
                result.isSuccessful(), result.getMessages().size());
            
            if (!result.isSuccessful()) {
                logger.info("🔍 ResourceProvider: Validation FAILED - messages:");
                result.getMessages().forEach(msg -> 
                    logger.info("🔍   {}: {} - {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                );
                
                if (bucketConfig.isStrictValidation()) {
                    // Strict mode: reject any validation errors
                    StringBuilder issues = new StringBuilder();
                    result.getMessages().forEach(msg -> issues.append(msg.getSeverity())
                            .append(": ")
                            .append(msg.getLocationString())
                            .append(" - ")
                            .append(msg.getMessage())
                            .append("\n"));
                    logger.error("🔍 ResourceProvider: Throwing UnprocessableEntityException for strict validation failure");
                    throw new UnprocessableEntityException("FHIR Validation failed (strict mode):\n" + issues.toString());
                } else if (bucketConfig.isLenientValidation()) {
                    // Lenient mode: log warnings but continue
                    logger.warn("FHIR Validation warnings for {} (lenient mode - continuing):", resourceClass.getSimpleName());
                    result.getMessages().forEach(msg -> 
                        logger.warn("  {}: {} - {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                }
            } else {
                logger.info("🔍 ResourceProvider: ✅ FHIR validation PASSED for {} in bucket {}", resourceClass.getSimpleName(), bucketName);
            }
        } else {
            logger.info("🔍 ResourceProvider: FHIR Validation DISABLED for bucket: {}", bucketName);
        }


        T created =  dao.create( resource.getClass().getSimpleName() , resource , bucketName).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resourceClass.getSimpleName(), created.getIdElement().getIdPart()));
        return outcome;
    }

    @Update
    public MethodOutcome update(@IdParam IdType theId, @ResourceParam T resource) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        // Ensure the resource ID matches the URL parameter
        String urlId = theId.getIdPart();
        resource.setId(urlId);
        
        logger.info("🔄 PUT {}: Processing update for ID {}", resourceClass.getSimpleName(), urlId);

        // Delegate to PutService for proper versioning and tombstone checking
        try {
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");
            com.couchbase.fhir.resources.service.TransactionContextImpl context = 
                new com.couchbase.fhir.resources.service.TransactionContextImpl(cluster, bucketName);
            
            @SuppressWarnings("unchecked")
            T updatedResource = (T) putService.updateOrCreateResource(resource, context);
            
            MethodOutcome outcome = new MethodOutcome();
            outcome.setResource(updatedResource);
            outcome.setId(new IdType(resourceClass.getSimpleName(), updatedResource.getIdElement().getIdPart()));
            
            // Determine if this was a create or update based on version
            String versionId = updatedResource.getMeta().getVersionId();
            if ("1".equals(versionId)) {
                outcome.setCreated(true); // New resource
                logger.info("✅ PUT {}: Created new resource with ID {}", resourceClass.getSimpleName(), urlId);
            } else {
                outcome.setCreated(false); // Updated existing
                logger.info("✅ PUT {}: Updated existing resource with ID {}, version {}", resourceClass.getSimpleName(), urlId, versionId);
            }
            
            return outcome;
            
        } catch (ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException e) {
            // ID was tombstoned - return 409
            throw e;
        } catch (Exception e) {
            logger.error("❌ PUT {}: Failed to update resource {}: {}", resourceClass.getSimpleName(), urlId, e.getMessage());
            throw new InternalErrorException("Failed to update resource: " + e.getMessage());
        }
    }

    @Delete
    public MethodOutcome delete(@IdParam IdType theId) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        String resourceId = theId.getIdPart();
        String resourceType = resourceClass.getSimpleName();
        
        logger.info("🗑️ DELETE {}: Processing soft delete for ID {}", resourceType, resourceId);

        // Delegate to DeleteService for proper tombstone handling
        try {
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");
            com.couchbase.fhir.resources.service.TransactionContextImpl context = 
                new com.couchbase.fhir.resources.service.TransactionContextImpl(cluster, bucketName);
            
            deleteService.deleteResource(resourceType, resourceId, context);
            
            // DELETE always returns 204 No Content (idempotent)
            MethodOutcome outcome = new MethodOutcome();
            // Note: No resource or ID set for DELETE - just success indication
            
            logger.info("✅ DELETE {}: Soft delete completed for ID {}", resourceType, resourceId);
            return outcome;
            
        } catch (Exception e) {
            logger.error("❌ DELETE {}: Failed to delete resource {}: {}", resourceType, resourceId, e.getMessage());
            throw new InternalErrorException("Failed to delete resource: " + e.getMessage());
        }
    }

        @ca.uhn.fhir.rest.annotation.Patch
    public MethodOutcome patch(@IdParam IdType theId, PatchTypeEnum patchType, @ResourceParam String patchBody) throws IOException {
        String resourceId = theId.getIdPart();
        String resourceType = resourceClass.getSimpleName();
        
        logger.info("🔧 ResourceProvider: Delegating PATCH for {}/{} to PatchService", resourceType, resourceId);
        
        // Validate patch type - we only support JSON Patch
        if (patchType != PatchTypeEnum.JSON_PATCH) {
            logger.error("❌ ResourceProvider: Unsupported patch type: {}", patchType);
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                "Only JSON Patch is supported. Received: " + patchType);
        }
        
        // Delegate to PatchService for all patch operations
        return patchService.patchResource(resourceType, resourceId, patchBody, resourceClass);
    }

    @Search(allowUnknownParams = true)
    public Bundle search(RequestDetails requestDetails) {
        String resourceType = resourceClass.getSimpleName();
        logger.info("🔍 ResourceProvider: Delegating search for {} to SearchService", resourceType);
        
        // Delegate to SearchService for all search operations
        return searchService.search(resourceType, requestDetails);
    }







    /**
     * FHIR $validate Operation - Validates a resource without storing it
     * POST /fhir/{bucket}/{ResourceType}/$validate
     */
    @Operation(name = "$validate", idempotent = false)
    public OperationOutcome validateResource(@ResourceParam T resource) {
        try {
            // Create a FHIR validator
            FhirValidator validator = fhirContext.newValidator();
            
            // Validate the resource
            ValidationResult result = validator.validateWithResult(resource);
            
            // Create OperationOutcome based on validation result
            OperationOutcome outcome = new OperationOutcome();
            
            if (result.isSuccessful()) {
                // Validation passed
                outcome.addIssue()
                    .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                    .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                    .setDiagnostics("Resource validation successful");
                    
            } else {
                // Validation failed - add all issues
                for (var issue : result.getMessages()) {
                    OperationOutcome.OperationOutcomeIssueComponent outcomeIssue = outcome.addIssue();
                    
                    // Map severity
                    switch (issue.getSeverity()) {
                        case ERROR:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            break;
                        case WARNING:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
                            break;
                        case INFORMATION:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
                            break;
                        default:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    }
                    
                    // Set issue type and message
                    outcomeIssue.setCode(OperationOutcome.IssueType.INVALID);
                    outcomeIssue.setDiagnostics(issue.getMessage());
                    
                    // Add location if available
                    if (issue.getLocationString() != null) {
                        outcomeIssue.addLocation(issue.getLocationString());
                    }
                }
            }
            
            return outcome;
            
        } catch (Exception e) {
            // Handle validation errors
            OperationOutcome errorOutcome = new OperationOutcome();
            errorOutcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.EXCEPTION)
                .setDiagnostics("Validation failed: " + e.getMessage());
            
            return errorOutcome;
        }
    }

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }


    




}
