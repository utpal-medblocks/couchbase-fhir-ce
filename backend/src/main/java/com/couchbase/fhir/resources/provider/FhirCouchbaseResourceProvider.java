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
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
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

    private final Class<T> resourceClass;
    private final FhirResourceDaoImpl<T> dao;
    private final FhirContext fhirContext;
    private final FhirSearchParameterPreprocessor searchPreprocessor;


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext, FhirSearchParameterPreprocessor searchPreprocessor) {
        this.resourceClass = resourceClass;
        this.dao = dao;
        this.fhirContext = fhirContext;
        this.searchPreprocessor = searchPreprocessor;
    }

    @Read
    public T read(@IdParam IdType theId) {
        String bucketName = TenantContextHolder.getTenantId();
        return dao.read(resourceClass.getSimpleName(), theId.getIdPart() , bucketName).orElseThrow(() ->
                new ResourceNotFoundException(theId));
    }

    @Create
    public MethodOutcome create(@ResourceParam T resource) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();

        if (resource.getIdElement().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }

        FhirAuditService auditService = new FhirAuditService();
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        auditService.addAuditInfoToMeta(resource, auditInfo, "CREATE");

       /* if (resource instanceof DomainResource) {
         //   ((DomainResource) resource).getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-" +  resource.getClass().getSimpleName().toLowerCase());
            ((DomainResource) resource).getMeta().setLastUpdated(new Date());
        }*/

        ValidationUtil validationUtil = new ValidationUtil();
        ValidationResult result = validationUtil.validate(resource , resourceClass.getSimpleName() , fhirContext);
        if (!result.isSuccessful()) {
            StringBuilder issues = new StringBuilder();
            result.getMessages().forEach(msg -> issues.append(msg.getSeverity())
                    .append(": ")
                    .append(msg.getLocationString())
                    .append(" - ")
                    .append(msg.getMessage())
                    .append("\n"));

            throw new UnprocessableEntityException("FHIR Validation failed:\n" + issues.toString());
        }


        T created =  dao.create( resource.getClass().getSimpleName() , resource , bucketName).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resourceClass.getSimpleName(), created.getIdElement().getIdPart()));
        return outcome;
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

        // STEP 2: Parse _summary and _elements parameters (FHIR standard)
        SummaryEnum summaryMode = parseSummaryParameter(searchParams);
        Set<String> elements = parseElementsParameter(searchParams);
        
        // Remove these parameters from the search params so they don't interfere with FTS queries
        searchParams.remove("_summary");
        searchParams.remove("_elements");

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

        Ftsn1qlQueryBuilder ftsn1qlQueryBuilder = new Ftsn1qlQueryBuilder();
        String query = ftsn1qlQueryBuilder.build(ftsQueries , mustNotQueries , resourceType , 0 , 50);

 //      QueryBuilder queryBuilder = new QueryBuilder();
//        List<T> results = dao.search(resourceType, queryBuilder.buildQuery(filters , revIncludes , resourceType , bucketName));
        List<T> results = dao.search(resourceType,query);

        // Construct a FHIR Bundle response with _summary and _elements support
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(results.size());

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
