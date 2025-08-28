package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.util.Ftsn1qlQueryBuilder.SortField;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import com.couchbase.fhir.resources.search.SearchState;
import com.couchbase.fhir.resources.search.SearchStateManager;
import com.couchbase.fhir.resources.search.RevIncludeParam;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.parser.IParser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized search service for FHIR resources with two primary modes:
 * 1. resolveOne() - Lightweight ID-only resolution for conditional operations
 * 2. search() - Full search operations returning complete Bundle responses
 */
@Service
public class SearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private FhirSearchParameterPreprocessor searchPreprocessor;
    
    @Autowired
    private com.couchbase.admin.connections.service.ConnectionService connectionService;
    
    @Autowired
    private FHIRResourceService serviceFactory;
    
    @Autowired
    private SearchStateManager searchStateManager;
    
    /**
     * Lightweight resolution for conditional operations.
     * Uses the same search logic but with LIMIT 2 for fast ambiguity detection.
     * 
     * NOTE: Race condition exists with FTS-based searches.
     * Rapid conditional PUTs may create duplicates due to FTS indexing delays.
     * This is a known limitation that requires alternative solutions.
     * 
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param criteria Search criteria as key-value pairs
     * @return ResolveResult indicating ZERO, ONE(id), or MANY matches
     */
    public ResolveResult resolveOne(String resourceType, Map<String, String> criteria) {
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.info("🔍 SearchService.resolveOne: {} with criteria: {}", resourceType, criteria);
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        
        // Build search queries using the same logic as regular search
        List<SearchQuery> ftsQueries = buildSearchQueries(resourceType, criteria);
        
        // Execute lightweight search with LIMIT 2
        try {
            Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
            String query = queryBuilder.build(ftsQueries, resourceType, 0, 2, null);  // LIMIT 2
            
            logger.debug("🔍 Resolve query: {}", query);
            
            // Get appropriate DAO for the resource type
            @SuppressWarnings("unchecked")
            Class<? extends Resource> resourceClassType = (Class<? extends Resource>) fhirContext.getResourceDefinition(resourceType).getImplementingClass();
            FhirResourceDaoImpl<?> dao = serviceFactory.getService(resourceClassType);
            
            @SuppressWarnings("unchecked")
            List<Resource> results = (List<Resource>) dao.search(resourceType, query);
            
            logger.info("🔍 SearchService.resolveOne: Found {} matches for {}", results.size(), resourceType);
            
            // Analyze results
            if (results.isEmpty()) {
                return ResolveResult.zero();
            } else if (results.size() == 1) {
                String resourceId = results.get(0).getIdElement().getIdPart();
                logger.info("🔍 SearchService.resolveOne: Single match found: {}", resourceId);
                return ResolveResult.one(resourceId);
            } else {
                logger.warn("🔍 SearchService.resolveOne: Multiple matches found ({}), returning MANY", results.size());
                return ResolveResult.many();
            }
            
        } catch (Exception e) {
            logger.error("❌ SearchService.resolveOne failed for {}: {}", resourceType, e.getMessage());
            throw new InvalidRequestException("Failed to resolve conditional operation: " + e.getMessage());
        }
    }
    
    /**
     * Full search operation returning complete Bundle with resources.
     * 
     * @param resourceType FHIR resource type
     * @param requestDetails HAPI FHIR request details containing search parameters
     * @return Bundle with matching resources
     */
    public Bundle search(String resourceType, RequestDetails requestDetails) {
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.info("🔍 SearchService.search: {} in bucket {}", resourceType, bucketName);
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        
        // Extract and validate parameters
        Map<String, String[]> rawParams = requestDetails.getParameters();
        Map<String, List<String>> allParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Arrays.asList(e.getValue())
                ));
        
        // Validate search parameters
        try {
            searchPreprocessor.validateSearchParameters(resourceType, allParams);
        } catch (FhirSearchValidationException e) {
            throw new InvalidRequestException(e.getUserFriendlyMessage());
        }
        
        // Flatten parameters for processing
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));
        
        // Parse FHIR standard parameters
        SummaryEnum summaryMode = parseSummaryParameter(searchParams);
        Set<String> elements = parseElementsParameter(searchParams);
        int count = parseCountParameter(searchParams);
        List<SortField> sortFields = parseSortParameter(searchParams);
        String totalMode = parseTotalParameter(searchParams);
        
        // Check for _revinclude parameter
        String revIncludeValue = searchParams.get("_revinclude");
        RevIncludeParam revIncludeParam = null;
        if (revIncludeValue != null) {
            revIncludeParam = RevIncludeParam.parse(revIncludeValue);
            if (revIncludeParam == null || !revIncludeParam.isValid()) {
                throw new InvalidRequestException("Invalid _revinclude parameter: " + revIncludeValue);
            }
        }
        
        // Remove control parameters from search criteria
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        searchParams.remove("_revinclude");
        
        // Build search queries
        List<SearchQuery> ftsQueries = buildSearchQueries(resourceType, searchParams);
        // Handle count-only queries
        if ("accurate".equals(totalMode) && count == 0) {
            return handleCountOnlyQuery(ftsQueries, resourceType, bucketName);
        }
        
        // Check if this is a _revinclude search
        if (revIncludeParam != null) {
            return handleRevIncludeSearch(resourceType, ftsQueries, revIncludeParam, count, 
                                        summaryMode, elements, totalMode, bucketName);
        }
        
        // Execute regular search
        return executeRegularSearch(resourceType, ftsQueries, count, sortFields, 
                                  summaryMode, elements, totalMode, bucketName);
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Build search queries from criteria parameters
     */
    private List<SearchQuery> buildSearchQueries(String resourceType, Map<String, String> criteria) {
        List<SearchQuery> ftsQueries = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        
        logger.debug("🔍 buildSearchQueries: Processing {} criteria for {}: {}", criteria.size(), resourceType, criteria);
        
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            String rawParamName = entry.getKey();
            String paramName = rawParamName;
            String modifier = null;
            
            int colonIndex = rawParamName.indexOf(':');
            if (colonIndex != -1) {
                paramName = rawParamName.substring(0, colonIndex);
                modifier = rawParamName.substring(colonIndex + 1);
            }
            
            // paramName is already resolved by HAPI FHIR's getSearchParam() method below
            String value = entry.getValue();
            
            if (paramName.equalsIgnoreCase("_revinclude")) {
                // Skip _revinclude for now in resolveOne mode
                continue;
            }
            
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(resourceType)
                    .getSearchParam(paramName);
            
            if (searchParam == null) continue;
            
            // Build appropriate query based on parameter type
            logger.debug("🔍 Processing parameter: {} = {} (type: {})", paramName, value, searchParam.getParamType());
            
            switch (searchParam.getParamType()) {
                case TOKEN:
                    SearchQuery tokenQuery = TokenSearchHelperFTS.buildTokenFTSQuery(fhirContext, resourceType, paramName, value);
                    ftsQueries.add(tokenQuery);
                    logger.debug("🔍 Added TOKEN query for {}: {}", paramName, tokenQuery.export());
                    break;
                case STRING:
                    SearchQuery stringQuery = StringSearchHelperFTS.buildStringFTSQuery(fhirContext, resourceType, paramName, value, searchParam, modifier);
                    ftsQueries.add(stringQuery);
                    logger.debug("🔍 Added STRING query for {}: {}", paramName, stringQuery.export());
                    break;
                case DATE:
                    SearchQuery dateQuery = DateSearchHelperFTS.buildDateFTS(fhirContext, resourceType, paramName, value);
                    ftsQueries.add(dateQuery);
                    logger.debug("🔍 Added DATE query for {}: {}", paramName, dateQuery.export());
                    break;
                case REFERENCE:
                    String referenceClause = ReferenceSearchHelper.buildReferenceWhereCluse(fhirContext, resourceType, paramName, value, searchParam);
                    filters.add(referenceClause);
                    logger.debug("🔍 Added REFERENCE filter for {}: {}", paramName, referenceClause);
                    break;
                case URI:
                case COMPOSITE:
                case QUANTITY:
                case HAS:
                case SPECIAL:
                case NUMBER:
                    // TODO: Implement support for these parameter types
                    logger.warn("Unsupported search parameter type: {} for parameter: {}", searchParam.getParamType(), paramName);
                    break;
            }
        }
        
        logger.debug("🔍 buildSearchQueries: Built {} FTS queries total", ftsQueries.size());
        return ftsQueries;
    }
    
    /**
     * Build lightweight ID-only query for resolveOne operations
     */
    private String buildResolveOneQuery(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        if (ftsQueries.isEmpty()) {
            // No search criteria - return simple query (no need to check deleted field)
            return String.format(
                "SELECT META().id as id FROM `%s`.`Resources`.`%s` LIMIT 2",
                bucketName, resourceType
            );
        }
        
        // Use FTS query but project only ID
        Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
        String fullQuery = queryBuilder.build(ftsQueries, resourceType, 0, 2);
        
        // Replace SELECT clause to get only META().id
        return fullQuery.replaceFirst(
            "SELECT resource\\.\\*",
            "SELECT META(resource).id as id"
        );
    }
    
    /**
     * Extract resource ID from query result
     */
    private String extractResourceId(JsonObject row) {
        String fullId = row.getString("id");
        if (fullId != null && fullId.contains("/")) {
            return fullId.substring(fullId.lastIndexOf("/") + 1);
        }
        return fullId;
    }
    
    /**
     * Handle count-only queries for _total=accurate with _count=0
     */
    private Bundle handleCountOnlyQuery(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        int totalCount = getAccurateCount(ftsQueries, resourceType, bucketName);
        
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(totalCount);
        
        return bundle;
    }
    
    /**
     * Get accurate count using FTS COUNT query
     */
    private int getAccurateCount(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        try {
            Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
            String countQuery = queryBuilder.buildCountQuery(ftsQueries, resourceType);
            
            Cluster cluster = connectionService.getConnection("default");
            QueryResult result = cluster.query(countQuery);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            if (!rows.isEmpty()) {
                return rows.get(0).getInt("total");
            }
            return 0;
        } catch (Exception e) {
            logger.warn("Failed to get accurate count for {}: {}", resourceType, e.getMessage());
            return 0;
        }
    }
    
    // ========== Parameter Parsing Methods (extracted from ResourceProvider) ==========
    
    private SummaryEnum parseSummaryParameter(Map<String, String> searchParams) {
        String summaryValue = searchParams.get("_summary");
        if (summaryValue == null || summaryValue.isEmpty()) {
            return null;
        }
        
        return switch (summaryValue.toLowerCase()) {
            case "true" -> SummaryEnum.TRUE;
            case "false" -> SummaryEnum.FALSE;
            case "text" -> SummaryEnum.TEXT;
            case "data" -> SummaryEnum.DATA;
            case "count" -> SummaryEnum.COUNT;
            default -> SummaryEnum.FALSE;
        };
    }
    
    private Set<String> parseElementsParameter(Map<String, String> searchParams) {
        String elementsValue = searchParams.get("_elements");
        if (elementsValue == null || elementsValue.isEmpty()) {
            return null;
        }
        
        Set<String> elements = new HashSet<>();
        String[] elementArray = elementsValue.split(",");
        
        for (String element : elementArray) {
            String trimmedElement = element.trim();
            if (!trimmedElement.isEmpty() && !trimmedElement.equals("*")) {
                elements.add(trimmedElement);
            }
        }
        
        return elements.isEmpty() ? null : elements;
    }
    
    private int parseCountParameter(Map<String, String> searchParams) {
        String countValue = searchParams.get("_count");
        if (countValue == null || countValue.isEmpty()) {
            return 20; // Default count
        }
        
        try {
            int count = Integer.parseInt(countValue);
            if (count <= 0) return 20;
            if (count > 100) return 100; // Maximum limit
            return count;
        } catch (NumberFormatException e) {
            return 20;
        }
    }
    
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
            
            boolean isDescending = field.startsWith("-");
            String fieldName = isDescending ? field.substring(1).trim() : field;
            
            if (fieldName.matches("^[a-zA-Z0-9._]+$")) {
                String mappedField = mapFhirFieldToFtsPath(fieldName);
                sortFields.add(new SortField(mappedField, isDescending));
            }
        }
        
        return sortFields;
    }
    
    private String parseTotalParameter(Map<String, String> searchParams) {
        String totalValue = searchParams.get("_total");
        if (totalValue == null || totalValue.isEmpty()) {
            return "none";
        }
        
        return switch (totalValue.toLowerCase()) {
            case "none", "estimate", "accurate" -> totalValue.toLowerCase();
            default -> "none";
        };
    }
    
    private String mapFhirFieldToFtsPath(String fhirField) {
        // Handle common FHIR field mappings
        return switch (fhirField) {
            case "_lastUpdated", "lastUpdated" -> "meta.lastUpdated";
            case "_id" -> "id";
            default -> fhirField;
        };
    }
    
    /**
     * Apply resource filtering based on _summary and _elements parameters
     */
    private Resource applyResourceFiltering(Resource resource, SummaryEnum summaryMode, Set<String> elements) {
        if (summaryMode == null && elements == null) {
            return resource;
        }
        
        try {
            IParser parser = fhirContext.newJsonParser();
            
            if (summaryMode != null) {
                switch (summaryMode) {
                    case TRUE:
                        parser.setSummaryMode(true);
                        break;
                    case COUNT:
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
                        // No summary mode - default behavior
                        break;
                }
            }
            
            if (elements != null && !elements.isEmpty()) {
                Set<String> encodeElements = new HashSet<>();
                encodeElements.add("id");
                encodeElements.add("resourceType");
                encodeElements.add("meta");
                
                String resourceType = resource.getClass().getSimpleName();
                for (String element : elements) {
                    encodeElements.add(resourceType + "." + element);
                }
                
                parser.setEncodeElements(encodeElements);
            }
            
            String filteredJson = parser.encodeResourceToString(resource);
            return parser.parseResource(resource.getClass(), filteredJson);
            
        } catch (Exception e) {
            logger.warn("Failed to apply resource filtering: {}", e.getMessage());
            return resource;
        }
    }
    
    /**
     * Execute regular search without _revinclude
     */
    private Bundle executeRegularSearch(String resourceType, List<SearchQuery> ftsQueries, int count,
                                      List<SortField> sortFields, SummaryEnum summaryMode, 
                                      Set<String> elements, String totalMode, String bucketName) {
        
        Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
        String query = queryBuilder.build(ftsQueries, resourceType, 0, count, sortFields);
        
        // Get appropriate DAO for the resource type
        @SuppressWarnings("unchecked")
        Class<? extends Resource> resourceClassType = (Class<? extends Resource>) fhirContext.getResourceDefinition(resourceType).getImplementingClass();
        FhirResourceDaoImpl<?> dao = serviceFactory.getService(resourceClassType);
        
        @SuppressWarnings("unchecked")
        List<Resource> results = (List<Resource>) dao.search(resourceType, query);
        
        // Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        
        // Set total count
        if ("accurate".equals(totalMode)) {
            int accurateTotal = getAccurateCount(ftsQueries, resourceType, bucketName);
            bundle.setTotal(accurateTotal);
        } else {
            bundle.setTotal(results.size());
        }
        
        // Add resources to bundle with filtering
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        logger.info("🔍 SearchService.search: Returning {} results for {}", results.size(), resourceType);
        return bundle;
    }
    
    /**
     * Handle _revinclude search with two-query strategy
     */
    private Bundle handleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                        RevIncludeParam revIncludeParam, int count,
                                        SummaryEnum summaryMode, Set<String> elements,
                                        String totalMode, String bucketName) {
        
        logger.info("🔍 Handling _revinclude search: {} -> {}:{}", 
                   primaryResourceType, revIncludeParam.getResourceType(), revIncludeParam.getSearchParam());
        
        // Step 1: Execute primary resource search to get IDs only
        List<String> primaryResourceIds = executeIdOnlySearch(primaryResourceType, ftsQueries, count, bucketName);
        
        if (primaryResourceIds.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        // Step 2: Calculate how many revinclude resources we need for first page
        int primaryResourceCount = primaryResourceIds.size();
        int revIncludeCount = count - primaryResourceCount;
        
        // Step 3: Search for revinclude resources
        List<Resource> revIncludeResources = executeRevIncludeResourceSearch(
            revIncludeParam.getResourceType(), revIncludeParam.getSearchParam(), 
            primaryResourceIds, revIncludeCount, bucketName);
        
        // Step 4: Get full primary resources
        List<Resource> primaryResources = getPrimaryResourcesByIds(primaryResourceType, primaryResourceIds, bucketName);
        
        // Step 5: Create search state for pagination
        SearchState searchState = SearchState.builder()
            .primaryResourceType(primaryResourceType)
            .primaryResourceIds(primaryResourceIds)
            .revIncludeResourceType(revIncludeParam.getResourceType())
            .revIncludeSearchParam(revIncludeParam.getSearchParam())
            .totalPrimaryResources(primaryResourceCount)
            .currentPrimaryOffset(primaryResourceCount) // All primary resources returned in first page
            .currentRevIncludeOffset(revIncludeCount)    // Next offset for revinclude resources
            .bucketName(bucketName)
            .build();
        
        // Get total count of revinclude resources for accurate pagination
        int totalRevIncludeCount = getTotalRevIncludeCount(
            revIncludeParam.getResourceType(), revIncludeParam.getSearchParam(), 
            primaryResourceIds, bucketName);
        searchState.setTotalRevIncludeResources(totalRevIncludeCount);
        
        String continuationToken = searchStateManager.storeSearchState(searchState);
        
        // Step 6: Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(primaryResourceCount + totalRevIncludeCount);
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add revinclude resources (search mode = "include")
        for (Resource resource : revIncludeResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(revIncludeParam.getResourceType() + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add next link if there are more revinclude resources
        if (searchState.hasMoreRevIncludeResources()) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count));
        }
        
        logger.info("🔍 Returning _revinclude bundle: {} primary + {} revinclude resources", 
                   primaryResourceCount, revIncludeResources.size());
        
        return bundle;
    }
    
    /**
     * Handle pagination requests for _revinclude searches
     */
    public Bundle handleRevIncludePagination(String continuationToken, int offset, int count) {
        SearchState searchState = searchStateManager.retrieveSearchState(continuationToken);
        
        if (searchState == null) {
            throw new InvalidRequestException("Invalid or expired search token");
        }
        
        if (searchState.isExpired()) {
            searchStateManager.removeSearchState(continuationToken);
            throw new InvalidRequestException("Search results have expired. Please repeat your original search.");
        }
        
        // Execute revinclude query for next batch
        List<Resource> revIncludeResources = executeRevIncludeResourceSearch(
            searchState.getRevIncludeResourceType(), 
            searchState.getRevIncludeSearchParam(),
            searchState.getPrimaryResourceIds(),
            count,
            searchState.getCurrentRevIncludeOffset(),
            searchState.getBucketName());
        
        // Update search state
        searchState.setCurrentRevIncludeOffset(searchState.getCurrentRevIncludeOffset() + count);
        
        // Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(searchState.getTotalPrimaryResources() + searchState.getTotalRevIncludeResources());
        
        // Add only revinclude resources (search mode = "include")
        for (Resource resource : revIncludeResources) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(searchState.getRevIncludeResourceType() + "/" + resource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add next link if there are more resources
        if (searchState.hasMoreRevIncludeResources()) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count));
        }
        
        return bundle;
    }
    
    // Helper methods for _revinclude implementation
    
    private List<String> executeIdOnlySearch(String resourceType, List<SearchQuery> ftsQueries, 
                                           int count, String bucketName) {
        Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
        String query = queryBuilder.build(ftsQueries, resourceType, 0, count, null);
        
        logger.debug("🔍 Original query: {}", query);
        
        // Modify query to select only IDs
        String idOnlyQuery = query.replace("SELECT resource.* ", "SELECT raw resource.id ");
        
        logger.debug("🔍 Modified ID-only query: {}", idOnlyQuery);
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            QueryResult result = cluster.query(idOnlyQuery);
            
            List<String> ids = new ArrayList<>();
            // With SELECT raw resource.id, the results are raw strings
            List<String> rawResults = result.rowsAs(String.class);
            ids.addAll(rawResults);
            
            logger.debug("🔍 ID-only search returned {} IDs for {}: {}", ids.size(), resourceType, ids);
            return ids;
            
        } catch (Exception e) {
            logger.error("Failed to execute ID-only search for {}: {}", resourceType, e.getMessage());
            throw new InvalidRequestException("Failed to execute search: " + e.getMessage());
        }
    }
    
    private List<Resource> executeRevIncludeResourceSearch(String resourceType, String searchParam,
                                                         List<String> primaryResourceIds, int count, 
                                                         String bucketName) {
        return executeRevIncludeResourceSearch(resourceType, searchParam, primaryResourceIds, count, 0, bucketName);
    }
    
    private List<Resource> executeRevIncludeResourceSearch(String resourceType, String searchParam,
                                                         List<String> primaryResourceIds, int count,
                                                         int offset, String bucketName) {
        
        // Build FTS query for revinclude resources
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        
        // Create disjunction for all primary resource references
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryId : primaryResourceIds) {
            String referenceValue = searchParam.equals("subject") ? 
                "Patient/" + primaryId : // Assuming primary resource is Patient for now
                primaryId;
            referenceQueries.add(SearchQuery.match(referenceValue).field(searchParam + ".reference"));
        }
        
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Execute the query
        Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
        String query = queryBuilder.build(revIncludeQueries, resourceType, offset, count, null);
        
        @SuppressWarnings("unchecked")
        Class<? extends Resource> resourceClassType = (Class<? extends Resource>) fhirContext.getResourceDefinition(resourceType).getImplementingClass();
        FhirResourceDaoImpl<?> dao = serviceFactory.getService(resourceClassType);
        
        @SuppressWarnings("unchecked")
        List<Resource> results = (List<Resource>) dao.search(resourceType, query);
        
        logger.debug("🔍 RevInclude search returned {} {} resources", results.size(), resourceType);
        return results;
    }
    
    private List<Resource> getPrimaryResourcesByIds(String resourceType, List<String> ids, String bucketName) {
        List<Resource> resources = new ArrayList<>();
        
        @SuppressWarnings("unchecked")
        Class<? extends Resource> resourceClassType = (Class<? extends Resource>) fhirContext.getResourceDefinition(resourceType).getImplementingClass();
        FhirResourceDaoImpl<?> dao = serviceFactory.getService(resourceClassType);
        
        for (String id : ids) {
            try {
                @SuppressWarnings("unchecked")
                Optional<Resource> resource = (Optional<Resource>) dao.read(resourceType, id, bucketName);
                resource.ifPresent(resources::add);
            } catch (Exception e) {
                logger.warn("Failed to retrieve {} with ID {}: {}", resourceType, id, e.getMessage());
            }
        }
        
        return resources;
    }
    
    private int getTotalRevIncludeCount(String resourceType, String searchParam, 
                                      List<String> primaryResourceIds, String bucketName) {
        // Build count query for revinclude resources
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryId : primaryResourceIds) {
            String referenceValue = searchParam.equals("subject") ? 
                "Patient/" + primaryId :
                primaryId;
            referenceQueries.add(SearchQuery.match(referenceValue).field(searchParam + ".reference"));
        }
        
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        Ftsn1qlQueryBuilder queryBuilder = new Ftsn1qlQueryBuilder();
        String countQuery = queryBuilder.buildCountQuery(revIncludeQueries, resourceType);
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            QueryResult result = cluster.query(countQuery);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            if (!rows.isEmpty()) {
                return rows.get(0).getInt("total");
            }
            return 0;
        } catch (Exception e) {
            logger.warn("Failed to get revinclude count for {}: {}", resourceType, e.getMessage());
            return 0;
        }
    }
    
    private Bundle createEmptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }
    
    private String buildNextPageUrl(String continuationToken, int count) {
        // This would typically build the full URL with proper base URL
        // For now, return a simple format
        return "?_getpages=" + continuationToken + "&_getpagesoffset=" + count + "&_count=" + count;
    }
}
