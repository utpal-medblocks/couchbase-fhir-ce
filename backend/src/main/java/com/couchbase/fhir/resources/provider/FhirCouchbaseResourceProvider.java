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
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.service.FhirAuditService;
import com.couchbase.fhir.resources.service.UserAuditInfo;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.AuditOp;
import com.couchbase.fhir.resources.service.MetaRequest;
import com.couchbase.common.fhir.FhirMetaHelper;

import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.util.Ftsn1qlQueryBuilder.SortField;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import com.couchbase.fhir.validation.ValidationUtil;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.ResourceParam;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
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
    private final FhirSearchParameterPreprocessor searchPreprocessor;
    private final FhirBucketValidator bucketValidator;
    private final FhirBucketConfigService configService;
    private final FhirValidator strictValidator; // Primary US Core validator
    private final FhirValidator lenientValidator; // Basic validator
    private final com.couchbase.admin.connections.service.ConnectionService connectionService;
    private final com.couchbase.fhir.resources.service.PutService putService;
    private final com.couchbase.fhir.resources.service.DeleteService deleteService;
    private final FhirMetaHelper metaHelper;


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext, FhirSearchParameterPreprocessor searchPreprocessor, FhirBucketValidator bucketValidator, FhirBucketConfigService configService, FhirValidator strictValidator, FhirValidator lenientValidator, com.couchbase.admin.connections.service.ConnectionService connectionService, com.couchbase.fhir.resources.service.PutService putService, com.couchbase.fhir.resources.service.DeleteService deleteService, FhirMetaHelper metaHelper) {
        this.resourceClass = resourceClass;
        this.dao = dao;
        this.fhirContext = fhirContext;
        this.searchPreprocessor = searchPreprocessor;
        this.bucketValidator = bucketValidator;
        this.configService = configService;
        this.strictValidator = strictValidator;
        this.lenientValidator = lenientValidator;
        this.connectionService = connectionService;
        this.putService = putService;
        this.deleteService = deleteService;
        this.metaHelper = metaHelper;
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

    @Search(allowUnknownParams = true)
    public Bundle search(RequestDetails requestDetails) {
        List<String> filters = new ArrayList<>();
        List<SearchQuery> ftsQueries = new ArrayList<>();
        List<String> revIncludes = new ArrayList<>();
        Map<String, String[]> rawParams = requestDetails.getParameters();
        
        // Convert to Map<String, List<String>> for validation
        Map<String, List<String>> allParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Arrays.asList(e.getValue())
                ));
        
        String resourceType = resourceClass.getSimpleName();
        String bucketName = TenantContextHolder.getTenantId();
        
        // STEP 0: Validate that this is a FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        // STEP 1: Validate search parameters BEFORE query execution
        try {
            searchPreprocessor.validateSearchParameters(resourceType, allParams);
        } catch (FhirSearchValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getUserFriendlyMessage());
        }
        
        // Flatten to Map<String, String> for existing logic
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));

        // STEP 2: Parse _summary, _elements, _count, _sort, and _total parameters (FHIR standard)
        SummaryEnum summaryMode = parseSummaryParameter(searchParams);
        Set<String> elements = parseElementsParameter(searchParams);
        int count = parseCountParameter(searchParams);
        List<SortField> sortFields = parseSortParameter(searchParams);
        String totalMode = parseTotalParameter(searchParams);
        
        // Remove these parameters from the search params so they don't interfere with FTS queries
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");

        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        for (Map.Entry<String, String> entry : searchParams.entrySet()) {
            String rawParamName = entry.getKey();
            String paramName = rawParamName;
            String modifier = null;
            int colonIndex = rawParamName.indexOf(':');

            if(colonIndex != -1){
                paramName = rawParamName.substring(0, colonIndex);
                modifier =  rawParamName.substring(colonIndex + 1);
            }
            String fhirParamName = QueryBuilder.getActualFieldName(fhirContext , resourceType, paramName);
            String value = entry.getValue();

            if(paramName.equalsIgnoreCase("_revinclude")){
                revIncludes.add(value);
            }else{
                RuntimeSearchParam searchParam = fhirContext
                        .getResourceDefinition(resourceType)
                        .getSearchParam(paramName);

                if (searchParam == null) continue;

                if (searchParam.getParamType() == RestSearchParameterTypeEnum.TOKEN) {
                    //filters.add(TokenSearchHelper.buildTokenWhereClause(fhirContext, resourceType, fhirParamName, value));
                    ftsQueries.add(TokenSearchHelperFTS.buildTokenFTSQuery(fhirContext, resourceType, fhirParamName, value));
                 }else if(searchParam.getParamType() == RestSearchParameterTypeEnum.STRING){
                    ftsQueries.add(StringSearchHelperFTS.buildStringFTSQuery(fhirContext, resourceType, fhirParamName, value, searchParam , modifier));
                }else if(searchParam.getParamType() == RestSearchParameterTypeEnum.DATE){
                    //String dateClause = DateSearchHelper.buildDateCondition(fhirContext , resourceType , fhirParamName , value);

                    ftsQueries.add(DateSearchHelperFTS.buildDateFTS(fhirContext, resourceType, fhirParamName, value));

                }else if(searchParam.getParamType() == RestSearchParameterTypeEnum.REFERENCE){
                    String referenceClause = ReferenceSearchHelper.buildReferenceWhereCluse(fhirContext , resourceType , fhirParamName , value , searchParam);
                    filters.add(referenceClause);
                }
            }
        }


        // Add must_not query to exclude deleted resources
        List<SearchQuery> mustNotQueries = new ArrayList<>();
        mustNotQueries.add(SearchQuery.booleanField(true).field("deleted"));

        // Handle count-only queries (_total=accurate with _count=0)
        if ("accurate".equals(totalMode) && count == 0) {
            return handleCountOnlyQuery(ftsQueries, mustNotQueries, resourceType, bucketName);
        }

        // Regular query with results
        Ftsn1qlQueryBuilder ftsn1qlQueryBuilder = new Ftsn1qlQueryBuilder();
        String query = ftsn1qlQueryBuilder.build(ftsQueries, mustNotQueries, resourceType, 0, count, sortFields);

        List<T> results = dao.search(resourceType, query);

        // Construct a FHIR Bundle response with _summary and _elements support
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        
        // Set accurate total count if requested
        if ("accurate".equals(totalMode)) {
            int accurateTotal = getAccurateCount(ftsQueries, mustNotQueries, resourceType, bucketName);
            bundle.setTotal(accurateTotal);
        } else {
            bundle.setTotal(results.size());
        }

        for (T resource : results) {
            // Apply filtering to the resource based on _summary and _elements parameters
            T filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart());
        }

        return bundle;
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

    // ========== _summary and _elements Support Methods ==========
    
    /**
     * Parse _summary parameter from search parameters (FHIR standard)
     */
    private SummaryEnum parseSummaryParameter(Map<String, String> searchParams) {
        String summaryValue = searchParams.get("_summary");
        if (summaryValue == null || summaryValue.isEmpty()) {
            return null;
        }
        
        try {
            switch (summaryValue.toLowerCase()) {
                case "true":
                    return SummaryEnum.TRUE;
                case "false":
                    return SummaryEnum.FALSE;
                case "text":
                    return SummaryEnum.TEXT;
                case "data":
                    return SummaryEnum.DATA;
                case "count":
                    return SummaryEnum.COUNT;
                default:
                    return SummaryEnum.FALSE;
            }
        } catch (Exception e) {
            return SummaryEnum.FALSE;
        }
    }
    
    /**
     * Parse _elements parameter from search parameters (FHIR standard)
     */
    private Set<String> parseElementsParameter(Map<String, String> searchParams) {
        String elementsValue = searchParams.get("_elements");
        if (elementsValue == null || elementsValue.isEmpty()) {
            return null;
        }
        
        try {
            Set<String> elements = new HashSet<>();
            String[] elementArray = elementsValue.split(",");
            
            for (String element : elementArray) {
                String trimmedElement = element.trim();
                if (!trimmedElement.isEmpty() && !trimmedElement.equals("*")) {
                    elements.add(trimmedElement);
                }
            }
            
            return elements.isEmpty() ? null : elements;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Parse _count parameter from search parameters (FHIR standard)
     * Default is 20, maximum is 100 for performance
     */
    private int parseCountParameter(Map<String, String> searchParams) {
        String countValue = searchParams.get("_count");
        if (countValue == null || countValue.isEmpty()) {
            return 20; // Default count reduced from 50 to 20
        }
        
        try {
            int count = Integer.parseInt(countValue);
            // Limit to reasonable bounds
            if (count <= 0) return 20;
            if (count > 100) return 100; // Maximum limit for performance
            return count;
        } catch (NumberFormatException e) {
            return 20; // Default on parse error
        }
    }
    
    /**
     * Parse _sort parameter from search parameters (FHIR standard)
     * Supports: _sort=field (ascending), _sort=-field (descending), _sort=field1,field2,-field3
     * Maps FHIR field names to proper FTS paths
     */
    private List<SortField> parseSortParameter(Map<String, String> searchParams) {
        String sortValue = searchParams.get("_sort");
        if (sortValue == null || sortValue.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<SortField> sortFields = new ArrayList<>();
        String[] fields = sortValue.split(",");
        
        for (String field : fields) {
            field = field.trim();
            if (field.isEmpty()) continue;
            
            // Check for descending order prefix
            boolean isDescending = field.startsWith("-");
            String fieldName = isDescending ? field.substring(1).trim() : field;
            
            // Only allow alphanumeric, dots, and underscores for security
            if (!fieldName.matches("^[a-zA-Z0-9._]+$")) {
                continue; // Skip invalid sort field
            }
            
            // Map FHIR field names to proper FTS paths
            String mappedField = mapFhirFieldToFtsPath(fieldName, resourceClass.getSimpleName());
            sortFields.add(new SortField(mappedField, isDescending));
        }
        
                return sortFields;
    }
    
    /**
     * Parse _total parameter from search parameters (FHIR standard)
     * Supports: _total=none (default), _total=estimate, _total=accurate
     */
    private String parseTotalParameter(Map<String, String> searchParams) {
        String totalValue = searchParams.get("_total");
        if (totalValue == null || totalValue.isEmpty()) {
            return "none"; // Default
        }
        
        switch (totalValue.toLowerCase()) {
            case "none":
            case "estimate":
            case "accurate":
                return totalValue.toLowerCase();
            default:
                return "none"; // Invalid value defaults to none
        }
    }
    
    /**
     * Handle count-only queries (_total=accurate with _count=0)
     */
    private Bundle handleCountOnlyQuery(List<SearchQuery> ftsQueries, List<SearchQuery> mustNotQueries, 
                                       String resourceType, String bucketName) {
        int totalCount = getAccurateCount(ftsQueries, mustNotQueries, resourceType, bucketName);
        
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(totalCount);
        // No entries - just the count
        
        return bundle;
    }
    
    /**
     * Get accurate count using FTS COUNT query
     */
    private int getAccurateCount(List<SearchQuery> ftsQueries, List<SearchQuery> mustNotQueries, 
                                String resourceType, String bucketName) {
        try {
            Ftsn1qlQueryBuilder ftsn1qlQueryBuilder = new Ftsn1qlQueryBuilder();
            String countQuery = ftsn1qlQueryBuilder.buildCountQuery(ftsQueries, mustNotQueries, resourceType);
            
            // Execute count query via DAO
            int count = dao.getCount(resourceType, countQuery);
            return count;
            
        } catch (Exception e) {
            // Fallback to estimated count or 0
            return 0;
        }
    }

    /**
     * Map FHIR field names to proper FTS index paths
     */
    private String mapFhirFieldToFtsPath(String fhirField, String resourceType) {
        // Handle common FHIR field mappings by resource type
        switch (resourceType) {
            case "Patient":
                switch (fhirField) {
                    case "family": return "name.family";  // Let query builder add .keyword
                    case "given": return "name.given";    // Let query builder add .keyword
                    case "birthdate": return "birthDate"; // Datetime - no .keyword needed
                    case "birthDate": return "birthDate"; // Datetime - no .keyword needed
                    case "gender": return "gender";       // Let query builder add .keyword
                    case "active": return "active";       // Boolean - no .keyword needed
                    default: break;
                }
                break;
                
            case "Observation":
                switch (fhirField) {
                    case "date": return "effectiveDateTime";
                    case "code": return "code.coding.code";
                    case "status": return "status";
                    default: break;
                }
                break;
                
            case "Encounter":
                switch (fhirField) {
                    case "date": return "period.start";
                    case "status": return "status";
                    case "class": return "class.code";
                    default: break;
                }
                break;
                
            // Add more resource-specific mappings as needed
        }
        
        // Handle common fields across all resources
        switch (fhirField) {
            case "_lastUpdated": return "meta.lastUpdated";
            case "lastUpdated": return "meta.lastUpdated";
            case "_id": return "id";
            default: return fhirField; // Use as-is if no mapping found
        }
    }
    
    /**
     * Apply resource filtering based on _summary and _elements parameters
     */
    @SuppressWarnings("unchecked")
    private T applyResourceFiltering(T resource, SummaryEnum summaryMode, Set<String> elements) {
        if (summaryMode == null && elements == null) {
            return resource; // No filtering needed
        }
        
        try {
            IParser parser = fhirContext.newJsonParser();
            
            // Apply summary mode
            if (summaryMode != null) {
                switch (summaryMode) {
                    case TRUE:
                        parser.setSummaryMode(true);
                        break;
                    case COUNT:
                        // For count mode, return minimal resource (just id and resourceType)
                        parser.setEncodeElements(Set.of("id", "resourceType"));
                        break;
                    case TEXT:
                        parser.setEncodeElements(Set.of("id", "resourceType", "text", "meta"));
                        break;
                    case DATA:
                        parser.setOmitResourceId(false);
                        parser.setSummaryMode(false);
                        break;
                    case FALSE:
                    default:
                        // No summary mode
                        break;
                }
            }
            
            // Apply elements filter
            if (elements != null && !elements.isEmpty()) {
                Set<String> encodeElements = new HashSet<>();
                
                // Always include mandatory fields
                encodeElements.add("id");
                encodeElements.add("resourceType");
                encodeElements.add("meta");
                
                // Add requested elements with resource type prefix (HAPI requirement)
                String resourceType = resource.getClass().getSimpleName();
                for (String element : elements) {
                    encodeElements.add(resourceType + "." + element);
                }
                
                parser.setEncodeElements(encodeElements);
            }
            
            // Serialize and deserialize to apply filtering
            String filteredJson = parser.encodeResourceToString(resource);
            return (T) parser.parseResource(resourceClass, filteredJson);
            
        } catch (Exception e) {
            // If filtering fails, return original resource
            return resource;
        }
    }

}
