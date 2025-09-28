package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.fhir.resources.interceptor.RequestPerfBagUtils;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import com.couchbase.fhir.resources.search.SearchQueryResult;
import com.couchbase.fhir.resources.search.SearchState;
import com.couchbase.fhir.resources.search.SearchStateManager;
import com.couchbase.fhir.resources.search.ChainParam;
import com.couchbase.fhir.resources.search.PaginationState;
import ca.uhn.fhir.model.api.Include;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.parser.IParser;
import com.couchbase.common.config.FhirConfig;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized search service for FHIR resources using the new FTS/KV pagination strategy.
 * Provides fast, scalable search operations with optimal pagination performance.
 */
@Service
public class SearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    
    // New pagination constants
    private static final int DEFAULT_PAGE_SIZE = 50;  // Up from 20
    private static final int MAX_FTS_FETCH_SIZE = 1000;  // Max doc keys per FTS query
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
    
    
    @Autowired
    private FhirConfig fhirConfig;
    
    @Autowired
    private FtsKvSearchService ftsKvSearchService;
    
    /**
     * Resolve conditional operations by finding matching resources.
     * Returns result indicating ZERO, ONE(id), or MANY matches for conditional operations.
     * 
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param criteria Search criteria as key-value pairs
     * @return ResolveResult indicating ZERO, ONE(id), or MANY matches
     */
    public ResolveResult resolveConditional(String resourceType, Map<String, List<String>> criteria) {
        logger.debug("🔍 Resolving conditional operation: {} with criteria: {}", resourceType, criteria);
        
        // Use regular search with small limit for conditional operations
        Bundle result = searchForConditional(resourceType, criteria, 2);
        
        int totalResults = result.getTotal();
        if (totalResults == 0) {
            return ResolveResult.zero();
        } else if (totalResults == 1) {
            String resourceId = result.getEntry().get(0).getResource().getIdElement().getIdPart();
            logger.debug("🔍 Conditional operation: Single match found: {}", resourceId);
            return ResolveResult.one(resourceId);
        } else {
            logger.warn("🔍 Conditional operation: Multiple matches found ({}), returning MANY", totalResults);
            return ResolveResult.many();
        }
    }
    
    /**
     * Internal search method for conditional operations with small result limit
     */
    private Bundle searchForConditional(String resourceType, Map<String, List<String>> criteria, int limit) {
        // Convert Map<String, List<String>> to Map<String, String[]> for search
        Map<String, String[]> searchParams = criteria.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toArray(new String[0])
                ));
        
        // Create minimal request details
        ca.uhn.fhir.rest.server.servlet.ServletRequestDetails requestDetails = 
            new ca.uhn.fhir.rest.server.servlet.ServletRequestDetails();
        requestDetails.setParameters(searchParams);
        
        // Override count parameter to limit results
        searchParams.put("_count", new String[]{String.valueOf(limit)});
        
        return search(resourceType, requestDetails);
    }
    
    /**
     * Direct search for Bundle processing (thin facade)
     */
    public Bundle searchDirect(String resourceType, Map<String, String[]> params) {
        ca.uhn.fhir.rest.server.servlet.ServletRequestDetails requestDetails = 
            new ca.uhn.fhir.rest.server.servlet.ServletRequestDetails();
        requestDetails.setParameters(params);
        return search(resourceType, requestDetails);
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
        
        // Track search start time in performance bag
        long searchStartMs = System.currentTimeMillis();
        
        logger.debug("🔍 SearchService.search: {} in bucket {} | reqId={}", resourceType, bucketName, 
            RequestPerfBagUtils.getCurrentRequestId(requestDetails));
        
        // Validate FHIR bucket
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw e; // Re-throw as-is since it already extends InvalidRequestException
        }
        
        // Extract and validate parameters
        Map<String, String[]> rawParams = requestDetails.getParameters();
        Map<String, List<String>> allParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Arrays.asList(e.getValue())
                ));

        // Debug: Log what HAPI knows about this resource type's search parameters
        // Commented out after confirming CareTeam search parameters - no longer needed
        /*
        try {
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            logger.info("🔍 {} search parameters known by HAPI:", resourceType);
            for (RuntimeSearchParam param : resourceDef.getSearchParams()) {
                logger.info("  - {} (type: {}, path: {})", param.getName(), param.getParamType(), param.getPath());
            }
        } catch (Exception e) {
            logger.warn("🔍 Failed to get HAPI search parameters for {}: {}", resourceType, e.getMessage());
        }
        */
        
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
    // Capture whether user explicitly provided _count BEFORE we strip control params
    boolean userExplicitCount = searchParams.containsKey("_count");
        List<SearchSort> sortFields = parseSortParameter(searchParams);
        String totalMode = parseTotalParameter(searchParams);
        
        // Check for _revinclude parameter using HAPI's Include class
        String revIncludeValue = searchParams.get("_revinclude");
        Include revInclude = null;
        if (revIncludeValue != null) {
            try {
                revInclude = new Include(revIncludeValue, true); // true = reverse include
                logger.info("🔍 Parsed HAPI RevInclude: {}", revInclude);
            } catch (Exception e) {
                throw new InvalidRequestException("Invalid _revinclude parameter: " + revIncludeValue + " - " + e.getMessage());
            }
        }
        
        // Check for _include parameter using HAPI's Include class
        String includeValue = searchParams.get("_include");
        Include include = null;
        if (includeValue != null) {
            try {
                include = new Include(includeValue);
                logger.debug("🔍 Parsed HAPI Include: {}", include);
            } catch (Exception e) {
                throw new InvalidRequestException("Invalid _include parameter: " + includeValue + " - " + e.getMessage());
            }
        }
        
        // Check for chained parameters (must be done before removing control parameters)
        ChainParam chainParam = detectChainParameter(searchParams, resourceType);
        
    // Remove control parameters from search criteria (we already captured userExplicitCount)
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        searchParams.remove("_revinclude");
        searchParams.remove("_include");
        
        // Remove chain parameter from search criteria if found
        if (chainParam != null) {
            searchParams.remove(chainParam.getOriginalParameter());
        }
        
        // Build search queries - use allParams to handle multiple values for the same parameter
        SearchQueryResult searchQueryResult = buildSearchQueries(resourceType, allParams);
        List<SearchQuery> ftsQueries = searchQueryResult.getFtsQueries();
        // Note: N1QL filters removed - using pure FTS/KV architecture
        
        logger.debug("🔍 SearchService: Built {} FTS queries for {}", ftsQueries.size(), resourceType);
        // Handle count-only queries
        if ("accurate".equals(totalMode) && count == 0) {
            Bundle result = handleCountOnlyQuery(ftsQueries, resourceType, bucketName);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a _revinclude search
        if (revInclude != null) {
            Bundle result = handleRevIncludeSearch(resourceType, ftsQueries, revInclude, count, 
                                        summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a _include search
        if (include != null) {
            Bundle result = handleIncludeSearch(resourceType, ftsQueries, include, count, 
                                     summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a chained search
        if (chainParam != null) {
            Bundle result = handleChainSearch(resourceType, ftsQueries, chainParam, count,
                                   sortFields, summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Always use the new pagination strategy for regular searches
        logger.info("🚀 Using new pagination strategy for {} search (userExplicitCount={})", resourceType, userExplicitCount);
        Bundle result = executeSearchWithNewPagination(resourceType, ftsQueries, count, sortFields, 
                                                      summaryMode, elements, totalMode, bucketName, requestDetails);
        // Track search timing
        RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
        RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
        return result;
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Detect if any parameter is a chained parameter
     */
    private ChainParam detectChainParameter(Map<String, String> searchParams, String resourceType) {
        for (Map.Entry<String, String> entry : searchParams.entrySet()) {
            String paramKey = entry.getKey();
            String paramValue = entry.getValue();
            
            // Skip control parameters
            if (paramKey.startsWith("_")) {
                continue;
            }
            
            ChainParam chainParam = ChainParam.parse(paramKey, paramValue, resourceType, fhirContext);
            if (chainParam != null) {
                logger.info("🔗 Detected chain parameter: {}", chainParam);
                return chainParam; // For now, support only one chain parameter per search
            }
        }
        
        return null;
    }
    
    /**
     * Build search queries from criteria parameters
     */
    private SearchQueryResult buildSearchQueries(String resourceType, Map<String, List<String>> criteria) {
        List<SearchQuery> ftsQueries = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        
        logger.debug("🔍 buildSearchQueries: Processing {} criteria for {}: {}", criteria.size(), resourceType, criteria);
        
        for (Map.Entry<String, List<String>> entry : criteria.entrySet()) {
            String rawParamName = entry.getKey();
            String paramName = rawParamName;
            String modifier = null;
            
            int colonIndex = rawParamName.indexOf(':');
            if (colonIndex != -1) {
                paramName = rawParamName.substring(0, colonIndex);
                modifier = rawParamName.substring(colonIndex + 1);
            }
            
            // paramName is already resolved by HAPI FHIR's getSearchParam() method below
            List<String> values = entry.getValue();
            
            
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(resourceType)
                    .getSearchParam(paramName);
            
            // Check for US Core parameters when HAPI doesn't know about them
            if (searchParam == null) {
                // Check if it's a US Core parameter
                boolean isUSCoreParam = fhirConfig.isValidUSCoreSearchParam(resourceType, paramName);
                if (isUSCoreParam) {
                    org.hl7.fhir.r4.model.SearchParameter usCoreParam = fhirConfig.getUSCoreSearchParamDetails(resourceType, paramName);
                    if (usCoreParam != null) {
                        logger.info("🔍 Found US Core parameter: {} for {}", paramName, resourceType);
                        logger.debug("🔍 US Core parameter details:");
                        logger.debug("   - Name: {}", usCoreParam.getName());
                        logger.debug("   - Code: {}", usCoreParam.getCode());
                        logger.debug("   - Expression: {}", usCoreParam.getExpression());
                        logger.debug("   - Type: {}", usCoreParam.getType());
                        
                        // Try to build US Core queries using the new helper
                        List<SearchQuery> usCoreQueries = USCoreSearchHelper.buildUSCoreFTSQueries(
                            fhirContext, resourceType, paramName, values, usCoreParam);
                        
                        if (usCoreQueries != null && !usCoreQueries.isEmpty()) {
                            ftsQueries.addAll(usCoreQueries);
                            logger.info("🔍 Added {} US Core queries for {}", usCoreQueries.size(), paramName);
                            for (SearchQuery query : usCoreQueries) {
                                logger.info("🔍   - {}", query.export());
                            }
                        } else {
                            logger.warn("🔍 Failed to build US Core queries for parameter: {}", paramName);
                        }
                    }
                }
                continue; // Still skip for now, just log what we found
            }
            
            // Build appropriate query based on parameter type
            logger.debug("🔍 Processing parameter: {} = {} (type: {})", paramName, values, searchParam.getParamType());
            
            switch (searchParam.getParamType()) {
                case TOKEN:
                    SearchQuery tokenQuery = TokenSearchHelper.buildTokenFTSQuery(fhirContext, resourceType, paramName, values.get(0));
                    ftsQueries.add(tokenQuery);
                    logger.debug("🔍 Added TOKEN query for {}: {}", paramName, tokenQuery.export());
                    break;
                case STRING:
                    SearchQuery stringQuery = StringSearchHelper.buildStringFTSQuery(fhirContext, resourceType, paramName, values.get(0), searchParam, modifier);
                    ftsQueries.add(stringQuery);
                    logger.debug("🔍 Added STRING query for {}: {}", paramName, stringQuery.export());
                    break;
                case DATE:
                    SearchQuery dateQuery = DateSearchHelper.buildDateFTS(fhirContext, resourceType, paramName, values);
                    if (dateQuery != null) {
                        ftsQueries.add(dateQuery);
                        logger.debug("🔍 Added DATE query for {}: {}", paramName, dateQuery.export());
                    }
                    break;
                case REFERENCE:
                    // Convert REFERENCE parameters to FTS queries
                    SearchQuery referenceQuery = ReferenceSearchHelper.buildReferenceFTSQuery(fhirContext, resourceType, paramName, values.get(0), searchParam);
                    if (referenceQuery != null) {
                        ftsQueries.add(referenceQuery);
                        logger.debug("🔍 Added REFERENCE FTS query for {}: {}", paramName, referenceQuery.export());
                    } else {
                        logger.warn("🔍 Failed to build FTS query for REFERENCE parameter: {}", paramName);
                    }
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
        
        logger.debug("🔍 buildSearchQueries: Built {} FTS queries (N1QL filters removed in FTS/KV architecture)", ftsQueries.size());
        return new SearchQueryResult(ftsQueries, filters);
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
     * Get accurate count using pure FTS count
     */
    private int getAccurateCount(List<SearchQuery> ftsQueries, String resourceType, String bucketName) {
        try {
            return (int) ftsKvSearchService.getCount(ftsQueries, resourceType);
        } catch (Exception e) {
            logger.error("❌ FTS count query failed for {}: {}", resourceType, e.getMessage());
            // Propagate the error instead of silently returning 0
            throw new InvalidRequestException("Search failed: " + e.getMessage(), e);
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
            return DEFAULT_PAGE_SIZE; // New default: 50
        }
        
        try {
            int count = Integer.parseInt(countValue);
            if (count <= 0) return DEFAULT_PAGE_SIZE;
            if (count > MAX_FTS_FETCH_SIZE) return MAX_FTS_FETCH_SIZE; // Max 1000
            return count;
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }
    
    private List<SearchSort> parseSortParameter(Map<String, String> searchParams) {
        String sortValue = searchParams.get("_sort");
        if (sortValue == null || sortValue.isEmpty()) {
            // Default sorting by meta.lastUpdated descending when no explicit sort specified
            List<SearchSort> defaultSort = new ArrayList<>();
            defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
            logger.debug("🔍 No explicit sort specified, using default: meta.lastUpdated desc");
            return defaultSort;
        }
        
        List<SearchSort> sortFields = new ArrayList<>();
        String[] fields = sortValue.split(",");
        
        for (String field : fields) {
            field = field.trim();
            if (field.isEmpty()) continue;
            
            boolean isDescending = field.startsWith("-");
            String fieldName = isDescending ? field.substring(1).trim() : field;
            
            if (fieldName.matches("^[a-zA-Z0-9._]+$")) {
                String mappedField = mapFhirFieldToFtsPath(fieldName);
                sortFields.add(SearchSort.byField(mappedField).desc(isDescending));
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
            case "name" -> "name.family";  // For Patient sorting, use family name
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
                
                String resourceType = resource.fhirType();
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
     * Execute search using new pagination strategy (FTS gets all keys, KV-only for subsequent pages)
     */
    private Bundle executeSearchWithNewPagination(String resourceType, List<SearchQuery> ftsQueries, int pageSize,
                                                 List<SearchSort> sortFields, SummaryEnum summaryMode, 
                                                 Set<String> elements, String totalMode, String bucketName,
                                                 RequestDetails requestDetails) {
        
        logger.info("🚀 New Pagination Strategy: {} (page size: {}, fetching up to 1000 keys from FTS)", resourceType, pageSize);
        
        // Step 1: Execute FTS to get ALL document keys (up to 1000)
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(
            ftsQueries, resourceType, sortFields);
        
        List<String> allDocumentKeys = ftsResult.getDocumentKeys();
        logger.info("🚀 FTS returned {} total document keys for {}", allDocumentKeys.size(), resourceType);
        
        // Step 2: Determine if pagination is needed
        boolean needsPagination = allDocumentKeys.size() > pageSize;
        
        // Step 3: Get documents for first page
        List<String> firstPageKeys = allDocumentKeys.size() <= pageSize ? 
            allDocumentKeys : 
            allDocumentKeys.subList(0, pageSize);
            
        List<Resource> results = ftsKvSearchService.getDocumentsFromKeys(firstPageKeys, resourceType);
        
        // Step 4: Create pagination state if needed
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            PaginationState paginationState = PaginationState.builder()
                .searchType("regular")
                .resourceType(resourceType)
                .allDocumentKeys(allDocumentKeys)
                .pageSize(pageSize)
                .currentOffset(pageSize) // Next page starts after first page
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ Created PaginationState: token={}, totalKeys={}, pages={}", 
                       continuationToken, allDocumentKeys.size(), paginationState.getTotalPages());
        }
        
        // Step 5: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(allDocumentKeys.size()); // Total is exact count from FTS
        
        // Add resources to bundle with filtering
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add next link if pagination is needed
        if (continuationToken != null && allDocumentKeys.size() > pageSize) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, pageSize, resourceType, bucketName, pageSize, extractBaseUrl(requestDetails, bucketName)));
        }
        
        logger.info("🚀 New Pagination: Returning {} results, total: {}, pages: {}", 
                   results.size(), allDocumentKeys.size(), needsPagination ? "multiple" : "single");
        return bundle;
    }
    
    /**
     * Handle continuation token for new pagination strategy (KV-only subsequent pages)
     */
    private Bundle handleContinuationTokenNewPagination(String continuationToken, String resourceType,
                                                       SummaryEnum summaryMode, Set<String> elements,
                                                       String bucketName, RequestDetails requestDetails) {
        
        logger.info("🔑 KV-Only Pagination: Processing continuation token for {}", resourceType);
        
        // Retrieve pagination state
        PaginationState paginationState = searchStateManager.getPaginationState(continuationToken);
        if (paginationState == null) {
            logger.warn("❌ Pagination state not found or expired for token: {}", continuationToken);
            throw new IllegalArgumentException("Invalid or expired continuation token");
        }
        
        // Get document keys for current page
        List<String> currentPageKeys = paginationState.getNextPageKeys();
        if (currentPageKeys.isEmpty()) {
            logger.info("🔑 No more results for pagination token: {}", continuationToken);
            return createEmptyBundle();
        }
        
        logger.info("🔑 KV-Only: Fetching {} documents for page {}/{}", 
                   currentPageKeys.size(), paginationState.getCurrentPage(), paginationState.getTotalPages());
        
        // For mixed resource types (like _revinclude), we need to group keys by resource type
        List<Resource> results = new ArrayList<>();
        if ("revinclude".equals(paginationState.getSearchType())) {
            // Group keys by resource type and retrieve from appropriate collections
            Map<String, List<String>> keysByResourceType = currentPageKeys.stream()
                .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
            
            for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
                String keyResourceType = entry.getKey();
                List<String> keysForType = entry.getValue();
                logger.debug("🔑 Retrieving {} {} documents", keysForType.size(), keyResourceType);
                List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
                results.addAll(resourcesForType);
            }
        } else {
            // Regular pagination - all keys are same resource type
            results = ftsKvSearchService.getDocumentsFromKeys(currentPageKeys, resourceType);
        }
        
        // Update pagination state for next page
        paginationState.setCurrentOffset(paginationState.getCurrentOffset() + currentPageKeys.size());
        
        // Check if this was the last page and cleanup if needed
        boolean isLastPage = !paginationState.hasMoreResults();
        if (isLastPage) {
            searchStateManager.removePaginationState(continuationToken);
            logger.info("🔑 Last page reached, cleaned up pagination state: {}", continuationToken);
        }
        
        // Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(paginationState.getTotalResults()); // Total is known from initial FTS
        
        // Add resources to bundle with filtering
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add next link if more results available
        if (paginationState.hasMoreResults()) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, paginationState.getPageSize(), 
                                           resourceType, bucketName, paginationState.getPageSize(), baseUrl));
        }
        
        logger.info("🔑 KV-Only Pagination: Returning {} results, page {}/{}, total: {}", 
                   results.size(), paginationState.getCurrentPage(), paginationState.getTotalPages(), 
                   paginationState.getTotalResults());
        return bundle;
    }
    
    /**
     * Handle _revinclude search with new pagination strategy
     */
    private Bundle handleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                        Include revInclude, int count,
                                        SummaryEnum summaryMode, Set<String> elements,
                                        String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.info("🔍 Handling _revinclude search with new pagination: {} -> {}", 
                   primaryResourceType, revInclude.getValue());
        
        // Step 1: Execute primary resource search to get ALL document keys (up to 1000)
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsKvSearchService.searchForAllKeys(
            ftsQueries, primaryResourceType, null); // No sorting for _revinclude
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        if (allPrimaryKeys.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.info("🔍 Found {} primary resource keys for _revinclude", allPrimaryKeys.size());
        
        // Step 2: Get primary resources for reference extraction (we need the actual resources to get references)
        List<Resource> allPrimaryResources = ftsKvSearchService.getDocumentsFromKeys(allPrimaryKeys, primaryResourceType);
        List<String> primaryResourceReferences = extractResourceReferences(allPrimaryResources);
        
        // Step 3: Execute _revinclude search to get ALL secondary resource keys
        // For _revinclude, the target type is the resource type that contains the reference (e.g., "Observation" in "Observation:subject")
        String revIncludeResourceType = revInclude.getParamType(); // This gives us "Observation" from "Observation:subject"
        // Build FTS query for revinclude resources to get keys only
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryReference : primaryResourceReferences) {
            referenceQueries.add(SearchQuery.match(primaryReference).field(revInclude.getParamName() + ".reference"));
        }
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Use default sorting for consistent ordering
        List<SearchSort> revIncludeSortFields = new ArrayList<>();
        revIncludeSortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        
        // Get ALL secondary resource keys (up to 1000)
        FtsSearchService.FtsSearchResult secondaryFtsResult = ftsKvSearchService.searchForAllKeys(
            revIncludeQueries, revIncludeResourceType, revIncludeSortFields);
        List<String> allSecondaryKeys = secondaryFtsResult.getDocumentKeys();
        
        logger.info("🔍 Found {} secondary resource keys for _revinclude", allSecondaryKeys.size());
        
        // Step 4: Combine all document keys (primary first, then secondary)
        List<String> allDocumentKeys = new ArrayList<>();
        allDocumentKeys.addAll(allPrimaryKeys);
        allDocumentKeys.addAll(allSecondaryKeys); // Secondary keys are already in correct format
        
        // Limit to MAX_FTS_FETCH_SIZE total
        if (allDocumentKeys.size() > MAX_FTS_FETCH_SIZE) {
            logger.info("🔍 Truncating _revinclude results from {} to {} (max limit)", 
                       allDocumentKeys.size(), MAX_FTS_FETCH_SIZE);
            allDocumentKeys = allDocumentKeys.subList(0, MAX_FTS_FETCH_SIZE);
        }
        
        // Step 5: Determine if pagination is needed
        boolean needsPagination = allDocumentKeys.size() > count;
        
        // Step 6: Get only the documents needed for the first page (OPTIMIZATION)
        List<String> firstPageKeys = allDocumentKeys.size() <= count ? 
            allDocumentKeys : 
            allDocumentKeys.subList(0, count);
        
        // Fetch documents for first page only - group by resource type for mixed collections
        List<Resource> firstPageResources = new ArrayList<>();
        Map<String, List<String>> keysByResourceType = firstPageKeys.stream()
            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
        
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String keyResourceType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 Fetching {} {} documents for first page", keysForType.size(), keyResourceType);
            List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
            firstPageResources.addAll(resourcesForType);
        }
        
        // Step 7: Create pagination state if needed
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .allDocumentKeys(allDocumentKeys)
                .pageSize(count)
                .currentOffset(count) // Next page starts after first page
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .primaryResourceCount(allPrimaryKeys.size()) // Track primary vs secondary split
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ Created PaginationState for _revinclude: token={}, totalKeys={}, primaryCount={}", 
                       continuationToken, allDocumentKeys.size(), allPrimaryKeys.size());
        }
        
        // Step 8: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(allDocumentKeys.size()); // Total is exact count
        
        // Add resources to bundle with appropriate search mode
        for (Resource resource : firstPageResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            
            // Determine if this is a primary or secondary resource
            Bundle.SearchEntryMode searchMode = resourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(searchMode);
        }
        
        // Add next link if pagination is needed
        if (continuationToken != null && allDocumentKeys.size() > count) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count, primaryResourceType, bucketName, count, extractBaseUrl(requestDetails, bucketName)));
        }
        
        logger.info("🔍 New _revinclude: Returning {} results, total: {}, primary: {}, secondary: {}", 
                   firstPageResources.size(), allDocumentKeys.size(), allPrimaryKeys.size(), 
                   allDocumentKeys.size() - allPrimaryKeys.size());
        return bundle;
    }
    
    /**
     * Handle _include search with two-query strategy
     */
    private Bundle handleIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                     Include include, int count,
                                     SummaryEnum summaryMode, Set<String> elements,
                                     String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.debug("🔍 Handling _include search: {} -> {}", primaryResourceType, include.getValue());
        logger.debug("🔍 HAPI Include details - ParamType: '{}', ParamName: '{}', ParamTargetType: '{}'", 
                   include.getParamType(), include.getParamName(), include.getParamTargetType());
        
        // Let's see what HAPI knows about this search parameter
        RuntimeSearchParam searchParam = fhirContext
                .getResourceDefinition(include.getParamType())
                .getSearchParam(include.getParamName());
        if (searchParam != null) {
            logger.debug("🔍 HAPI SearchParam details - Path: '{}', Type: '{}'", 
                       searchParam.getPath(), searchParam.getParamType());
        }
        
        // HAPI's Include already provides the target resource type
        String targetResourceType = include.getParamTargetType();
        if (targetResourceType == null) {
            // Fallback: use HAPI's search parameter definitions
            targetResourceType = determineTargetResourceType(include.getParamType(), include.getParamName());
        }
        logger.debug("🔍 Target resource type for inclusion: '{}'", targetResourceType);

        // Step 1: Execute primary resource search using new pagination strategy
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(ftsQueries, primaryResourceType, null);
        List<String> firstPageKeys = ftsResult.getDocumentKeys().size() <= count ? 
            ftsResult.getDocumentKeys() : ftsResult.getDocumentKeys().subList(0, count);
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(firstPageKeys, primaryResourceType);
        
        if (primaryResources.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        // Step 2: Extract reference IDs from primary resources
        logger.info("🔍 Found {} primary resources, extracting references for parameter '{}'", 
                   primaryResources.size(), include.getParamName());
        List<String> includeResourceIds = extractReferenceIds(primaryResources, include.getParamName());
        
        if (includeResourceIds.isEmpty()) {
            logger.warn("🔍 No reference IDs found in {} primary resources for parameter '{}', returning primary resources only", 
                       primaryResources.size(), include.getParamName());
            return buildBundleWithPrimaryResourcesOnly(primaryResources, primaryResourceType, summaryMode, elements);
        }
        
        logger.debug("🔍 Extracted {} reference IDs: {}", includeResourceIds.size(), includeResourceIds);
        
        // Step 3: Get included resources by their IDs  
        logger.debug("🔍 Looking up {} {} resources by IDs: {}", includeResourceIds.size(), targetResourceType, includeResourceIds);
        List<Resource> includedResources = getResourcesByIds(targetResourceType, includeResourceIds, bucketName);
        logger.debug("🔍 Found {} {} resources", includedResources.size(), targetResourceType);
        
        // Step 4: Create search state for pagination (reuse revInclude fields for include)
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        SearchState searchState = SearchState.builder()
            .searchType("include")
            .primaryResourceType(primaryResourceType)
            .originalSearchCriteria(new HashMap<>()) // We'll need the original search criteria
            .cachedFtsQueries(new ArrayList<>(ftsQueries))
            .revIncludeResourceType(targetResourceType) // Reuse for include target type
            .revIncludeSearchParam(include.getParamName()) // Reuse for include param name
            .totalPrimaryResources(primaryResources.size()) // Use current primary resources count
            .currentPrimaryOffset(primaryResources.size()) // Current primary resources processed
            .pageSize(count)
            .bucketName(bucketName)
            .baseUrl(baseUrl)
            .build();
        
        // Get total count of included resources (all Patient IDs that could be included)
        int totalIncludedCount = includedResources.size(); // For now, just what we found on this page
        searchState.setTotalRevIncludeResources(totalIncludedCount); // Reuse field
        searchState.setCurrentRevIncludeOffset(0); // No include resource pagination yet
        
        String continuationToken = searchStateManager.storeSearchState(searchState);
        
        // Step 5: Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(primaryResources.size()); // Only count primary resources, not included ones
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add included resources (search mode = "include")
        for (Resource resource : includedResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(targetResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add next link if there are more primary resources to paginate through
        // For _include, we paginate through primary resources and include their references on each page
        if (primaryResources.size() == count) {
            // Assume there might be more primary resources (we'd need total count for accuracy)
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentPrimaryOffset(), primaryResourceType, bucketName, count, searchState.getBaseUrl()));
        }
        
        logger.debug("🔍 Returning _include bundle: {} primary + {} included resources", 
                   primaryResources.size(), includedResources.size());
        
        return bundle;
    }
    
    /**
     * Handle chained search with two-query strategy
     */
    private Bundle handleChainSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                   ChainParam chainParam, int count, List<SearchSort> sortFields,
                                   SummaryEnum summaryMode, Set<String> elements,
                                   String totalMode, String bucketName, RequestDetails requestDetails) {

        logger.debug("🔗 Handling chained search: {} with chain {}", primaryResourceType, chainParam);

        // Step 1: Execute chain query to find referenced resource IDs
        List<String> referencedResourceIds = executeChainQuery(
            chainParam.getTargetResourceType(),
            chainParam.getSearchParam(),
            chainParam.getValue(),
            bucketName
        );
        
        if (referencedResourceIds.isEmpty()) {
            logger.warn("🔗 No referenced resources found for chain query, returning empty bundle");
            return createEmptyBundle();
        }

        logger.debug("🔗 Chain query found {} referenced {} resources: {}", 
                   referencedResourceIds.size(), chainParam.getTargetResourceType(), referencedResourceIds);
        
        // Step 2: Execute primary search for resources that reference the found IDs
        List<Resource> primaryResources = executePrimaryChainSearch(
            primaryResourceType,
            chainParam.getReferenceFieldPath(),
            chainParam.getTargetResourceType(),
            referencedResourceIds,
            ftsQueries, // Additional search criteria
            count,
            sortFields,
            bucketName
        );
        
        // Step 3: Get total count for accurate pagination (if needed)
        int totalPrimaryResourceCount = getTotalChainSearchCount(
            primaryResourceType,
            chainParam.getReferenceFieldPath(),
            chainParam.getTargetResourceType(),
            referencedResourceIds,
            ftsQueries,
            bucketName
        );
        
        // Step 4: Create search state for pagination
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        SearchState searchState = SearchState.builder()
            .searchType("chain")
            .primaryResourceType(primaryResourceType)
            .originalSearchCriteria(Map.of(chainParam.getOriginalParameter(), chainParam.getValue()))
            .cachedFtsQueries(new ArrayList<>(ftsQueries))
            .sortFields(sortFields != null ? new ArrayList<>(sortFields) : new ArrayList<>())
            .totalPrimaryResources(totalPrimaryResourceCount)
            .currentPrimaryOffset(primaryResources.size())
            .pageSize(count)
            .bucketName(bucketName)
            .baseUrl(baseUrl)
            // Store chain-specific data using existing fields
            .revIncludeResourceType(chainParam.getTargetResourceType()) // Reuse for chain target type
            .revIncludeSearchParam(chainParam.getReferenceFieldPath()) // Store resolved reference field path for pagination
            .primaryResourceIds(referencedResourceIds) // Store referenced resource IDs
            .build();
        
        String continuationToken = searchStateManager.storeSearchState(searchState);
        
        // Step 5: Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(totalPrimaryResourceCount);
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add next link if there are more results
        if (primaryResources.size() == count && searchState.getCurrentPrimaryOffset() < totalPrimaryResourceCount) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentPrimaryOffset(), primaryResourceType, bucketName, count, searchState.getBaseUrl()));
        }
        
        logger.debug("🔗 Returning chained search bundle: {} results, total: {}", primaryResources.size(), totalPrimaryResourceCount);
        return bundle;
    }
    
    /**
     * Handle pagination requests for both legacy and new pagination strategies
     */
    public Bundle handleRevIncludePagination(String continuationToken, int offset, int count) {
        // First, try the new pagination strategy
        PaginationState paginationState = searchStateManager.getPaginationState(continuationToken);
        if (paginationState != null) {
            logger.info("🔑 Using new pagination strategy for token: {}", continuationToken);
            // For new pagination, we ignore offset/count from URL and use the stored page size
            // This is because the new strategy manages pagination internally
            return handleContinuationTokenNewPagination(continuationToken, paginationState.getResourceType(),
                                                       SummaryEnum.FALSE, null, // TODO: Store these in PaginationState if needed
                                                       paginationState.getBucketName(), null); // TODO: Store RequestDetails if needed
        }
        
        // Fall back to legacy SearchState handling
        SearchState searchState = searchStateManager.retrieveSearchState(continuationToken);
        
        if (searchState == null) {
            throw new InvalidRequestException("Invalid or expired search token");
        }
        
        if (searchState.isExpired()) {
            searchStateManager.removeSearchState(continuationToken);
            throw new InvalidRequestException("Search results have expired. Please repeat your original search.");
        }
        
        logger.info("🔍 Using legacy pagination strategy for token: {}", continuationToken);
        
        // Route to appropriate pagination handler based on search type
        if (searchState.isRevIncludeSearch()) {
            return handleRevIncludePaginationInternal(searchState, continuationToken, offset, count);
        } else if (searchState.isRegularSearch()) {
            return handleRegularPaginationInternal(searchState, continuationToken, offset, count);
        } else if ("include".equals(searchState.getSearchType())) {
            return handleIncludePaginationInternal(searchState, continuationToken, offset, count);
        } else if ("chain".equals(searchState.getSearchType())) {
            return handleChainPaginationInternal(searchState, continuationToken, offset, count);
        } else {
            throw new InvalidRequestException("Unknown search type: " + searchState.getSearchType());
        }
    }
    
    /**
     * Handle pagination for _revinclude searches
     */
    private Bundle handleRevIncludePaginationInternal(SearchState searchState, String continuationToken, int offset, int count) {
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
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentRevIncludeOffset(), searchState.getRevIncludeResourceType(), searchState.getBucketName(), searchState.getPageSize(), searchState.getBaseUrl()));
        }
        
        return bundle;
    }
    
    /**
     * Handle pagination for regular searches
     */
    private Bundle handleRegularPaginationInternal(SearchState searchState, String continuationToken, int offset, int count) {
        logger.debug("🔍 Handling regular pagination: offset={}, count={}", offset, count);
        
        // Execute query using cached FTS queries with new pagination strategy
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(
            searchState.getCachedFtsQueries(), searchState.getPrimaryResourceType(), searchState.getSortFields());
        List<String> allKeys = ftsResult.getDocumentKeys();
        int fromIndex = Math.min(offset, allKeys.size());
        int toIndex = Math.min(offset + count, allKeys.size());
        List<String> pageKeys = fromIndex < toIndex ? allKeys.subList(fromIndex, toIndex) : List.of();
        List<Resource> results = ftsKvSearchService.getDocumentsFromKeys(pageKeys, searchState.getPrimaryResourceType());
        
        // Update search state
        searchState.setCurrentPrimaryOffset(offset + results.size());
        
        // Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(searchState.getTotalPrimaryResources());
        
        // Add resources to bundle
        for (Resource resource : results) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(searchState.getPrimaryResourceType() + "/" + resource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add next link if there are more results
        if (searchState.hasMoreRegularResults()) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentPrimaryOffset(), searchState.getPrimaryResourceType(), searchState.getBucketName(), searchState.getPageSize(), searchState.getBaseUrl()));
        }
        
        logger.info("🔍 Returning regular pagination: {} results", results.size());
        return bundle;
    }
    
    /**
     * Handle pagination for _include searches
     */
    private Bundle handleIncludePaginationInternal(SearchState searchState, String continuationToken, int offset, int count) {
        logger.debug("🔍 Handling include pagination: offset={}, count={}", offset, count);
        
        // Execute query for next batch of primary resources using new pagination strategy
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(
            searchState.getCachedFtsQueries(), searchState.getPrimaryResourceType(), searchState.getSortFields());
        List<String> allKeys = ftsResult.getDocumentKeys();
        int fromIndex = Math.min(offset, allKeys.size());
        int toIndex = Math.min(offset + count, allKeys.size());
        List<String> pageKeys = fromIndex < toIndex ? allKeys.subList(fromIndex, toIndex) : List.of();
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(pageKeys, searchState.getPrimaryResourceType());
        
        // Extract reference IDs from this batch of primary resources
        List<String> includeResourceIds = extractReferenceIds(primaryResources, searchState.getRevIncludeSearchParam());
        
        // Get included resources by their IDs
        List<Resource> includedResources = new ArrayList<>();
        if (!includeResourceIds.isEmpty()) {
            includedResources = getResourcesByIds(searchState.getRevIncludeResourceType(), includeResourceIds, searchState.getBucketName());
        }
        
        // Update search state
        searchState.setCurrentPrimaryOffset(offset + primaryResources.size());
        
        // Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(searchState.getTotalPrimaryResources() + includedResources.size()); // Approximate total
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(searchState.getPrimaryResourceType() + "/" + resource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add included resources (search mode = "include")
        for (Resource resource : includedResources) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(searchState.getRevIncludeResourceType() + "/" + resource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add next link if there are more primary resources
        if (primaryResources.size() == count) {
            // Assume there might be more (we'd need accurate total count)
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentPrimaryOffset(), searchState.getPrimaryResourceType(), searchState.getBucketName(), searchState.getPageSize(), searchState.getBaseUrl()));
        }
        
        logger.info("🔍 Returning include pagination: {} primary + {} included resources", primaryResources.size(), includedResources.size());
        return bundle;
    }
    
    // Helper methods for _revinclude implementation
    
    /**
     * Extract full resource references from a list of resources
     */
    private List<String> extractResourceReferences(List<Resource> resources) {
        return resources.stream()
                .map(resource -> resource.fhirType() + "/" + resource.getIdElement().getIdPart())
                .collect(Collectors.toList());
    }
    
    
    
    private List<Resource> executeRevIncludeResourceSearch(String resourceType, String searchParam,
                                                         List<String> primaryResourceIds, int count,
                                                         int offset, String bucketName) {
        return executeRevIncludeResourceSearch(resourceType, searchParam, primaryResourceIds, count, offset, bucketName, null);
    }
    
    private List<Resource> executeRevIncludeResourceSearch(String resourceType, String searchParam,
                                                         List<String> primaryResourceIds, int count,
                                                         int offset, String bucketName, RequestDetails requestDetails) {
        
        // Build FTS query for revinclude resources
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        
        // Create disjunction for all primary resource references
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryReference : primaryResourceIds) {
            // primaryReference is already in format "CarePlan/1234", use it directly
            referenceQueries.add(SearchQuery.match(primaryReference).field(searchParam + ".reference"));
        }
        
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // No automatic sorting for revinclude - preserve FTS relevance order
        List<SearchSort> sortFields = null;
        
        // Execute the query using FTS/KV architecture instead of N1QL
        List<Resource> results;
        // Execute revinclude search using new pagination strategy
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(revIncludeQueries, resourceType, sortFields);
        List<String> allKeys = ftsResult.getDocumentKeys();
        int fromIndex = Math.min(offset, allKeys.size());
        int toIndex = Math.min(offset + count, allKeys.size());
        List<String> pageKeys = fromIndex < toIndex ? allKeys.subList(fromIndex, toIndex) : List.of();
        results = ftsKvSearchService.getDocumentsFromKeys(pageKeys, resourceType);
        
        logger.debug("🔍 RevInclude search returned {} {} resources", results.size(), resourceType);
        return results;
    }
    
    private Bundle createEmptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }
    
    /**
     * Extract base URL from RequestDetails for pagination links
     * Format: http://hostname:port/fhir/bucketName
     */
    private String extractBaseUrl(RequestDetails requestDetails, String bucketName) {
        if (requestDetails == null) {
            return null;
        }
        
        try {
            String completeUrl = requestDetails.getCompleteUrl();
            if (completeUrl != null) {
                // Extract everything up to and including /fhir/bucketName
                // Example: http://ec2-13-219-88-60.compute-1.amazonaws.com/fhir/test/Patient?...
                // Should return: http://ec2-13-219-88-60.compute-1.amazonaws.com/fhir/test
                int fhirIndex = completeUrl.indexOf("/fhir/");
                if (fhirIndex != -1) {
                    int bucketEndIndex = completeUrl.indexOf("/", fhirIndex + 6); // 6 = length of "/fhir/"
                    if (bucketEndIndex != -1) {
                        return completeUrl.substring(0, bucketEndIndex);
                    } else {
                        // No resource type after bucket, might be a bucket-level request
                        int queryIndex = completeUrl.indexOf("?", fhirIndex + 6);
                        if (queryIndex != -1) {
                            return completeUrl.substring(0, queryIndex);
                        } else {
                            return completeUrl;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract base URL from request details: {}", e.getMessage());
        }
        
        return null;
    }
    
    private String buildNextPageUrl(String continuationToken, int offset, String resourceType, String bucketName, int count, String baseUrl) {
        // Use provided base URL or fall back to localhost
        if (baseUrl == null) {
            baseUrl = "http://localhost:8080/fhir/" + bucketName;
        }
        
        // Use different parameter names that HAPI might handle better
        // Use _page instead of _getpages to avoid HAPI validation issues
        return baseUrl + "/" + resourceType + "?_page=" + continuationToken + 
               "&_offset=" + offset + "&_count=" + count;
    }
    
    // ========== Regular Search Pagination Methods ==========
    
    /**
     * Determine if a search should use pagination-ready flow
     * This determines if we should use the flow that CAN create SearchState if needed,
     * but SearchState will only be created if results.size() == count
     */
    private boolean shouldPaginate(Map<String, String> searchParams, int count) {
        // Use pagination-ready flow if _count is explicitly set and reasonable
        // This allows us to create SearchState ONLY if we get exactly 'count' results
        if (count > 0 && count <= 1000) {
            return true;
        }
        
        // Use pagination-ready flow for potentially large result sets
        // But SearchState will only be created if results indicate more data exists
        if (hasLargeResultPotential(searchParams)) {
            return true;
        }
        
        // Default to non-paginated for simple, specific searches
        return false;
    }
    
    /**
     * Check if search parameters indicate potentially large result sets
     */
    private boolean hasLargeResultPotential(Map<String, String> searchParams) {
        // Broad searches that could return many results
        if (searchParams.containsKey("name") || 
            searchParams.containsKey("family") || 
            searchParams.containsKey("given") ||
            searchParams.containsKey("birthdate")) {
            return true;
        }
        
        // Few search criteria = potentially large results
        if (searchParams.size() <= 2) {
            return true;
        }
        
        return false;
    }
    
    
    
    
    // ========== Include Search Helper Methods ==========
    
    /**
     * Determine target resource type when HAPI's Include doesn't provide it
     */
    private String determineTargetResourceType(String paramType, String paramName) {
        try {
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(paramType)
                    .getSearchParam(paramName);
            
            if (searchParam != null) {
                // Extract target types from the path
                String path = searchParam.getPath();
                if (path != null) {
                    if (path.contains("is Patient")) return "Patient";
                    if (path.contains("is Practitioner")) return "Practitioner";
                    if (path.contains("is Organization")) return "Organization";
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to determine target resource type from HAPI: {}", e.getMessage());
        }
        
        // Hardcoded fallback for common cases
        if ("patient".equals(paramName) || "subject".equals(paramName)) {
            return "Patient";
        } else if ("practitioner".equals(paramName)) {
            return "Practitioner";
        } else if ("organization".equals(paramName)) {
            return "Organization";
        }
        
        // Default: capitalize the parameter name
        return paramName.substring(0, 1).toUpperCase() + paramName.substring(1);
    }
    
    /**
     * Extract reference IDs from primary resources based on the search parameter
     */
    private List<String> extractReferenceIds(List<Resource> primaryResources, String searchParam) {
        List<String> referenceIds = new ArrayList<>();
        
        logger.debug("🔍 Extracting reference IDs for parameter '{}' from {} resources", searchParam, primaryResources.size());
        
        for (int i = 0; i < primaryResources.size(); i++) {
            Resource resource = primaryResources.get(i);
            try {
                logger.debug("🔍 Processing resource {}/{}: {} (ID: {})", 
                           i + 1, primaryResources.size(), 
                           resource.fhirType(), 
                           resource.getIdElement().getIdPart());
                
                // Use reflection to get the field value
                // For now, we'll handle common cases like "subject", "patient", etc.
                String referenceValue = extractReferenceFromResource(resource, searchParam);
                logger.debug("🔍 Reference value for '{}': {}", searchParam, referenceValue);
                
                if (referenceValue != null) {
                    // Extract ID from reference (e.g., "Patient/123" -> "123")
                    String id = extractIdFromReference(referenceValue);
                    logger.debug("🔍 Extracted ID: {}", id);
                    
                    if (id != null && !referenceIds.contains(id)) {
                        referenceIds.add(id);
                        logger.debug("🔍 Added ID to list: {}", id);
                    }
                } else {
                    logger.debug("🔍 No reference value found for parameter '{}' in resource {}", searchParam, resource.fhirType());
                }
            } catch (Exception e) {
                logger.warn("Failed to extract reference from {}: {}", resource.fhirType(), e.getMessage());
            }
        }
        
        logger.debug("🔍 Extracted {} reference IDs from {} primary resources: {}", referenceIds.size(), primaryResources.size(), referenceIds);
        return referenceIds;
    }
    
    /**
     * Extract reference value from a resource using the search parameter
     */
    private String extractReferenceFromResource(Resource resource, String searchParam) {
        logger.debug("🔍 Looking for reference field '{}' in resource {}", searchParam, resource.fhirType());
        
        // Get the HAPI search parameter to resolve the correct field path
        try {
            RuntimeSearchParam hapiSearchParam = fhirContext
                    .getResourceDefinition(resource.fhirType())
                    .getSearchParam(searchParam);
            
            if (hapiSearchParam != null && hapiSearchParam.getPath() != null) {
                String path = hapiSearchParam.getPath();
                logger.debug("🔍 HAPI path for '{}': {}", searchParam, path);
                
                // Use FHIRPathParser to resolve casting expressions
                FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(path);
                String fieldPath = parsed.getPrimaryFieldPath();
                
                if (fieldPath != null) {
                    logger.debug("🔍 Resolved field path: {}", fieldPath);

                    // Convert field path to getter method name
                    String methodName = fieldPathToGetterMethod(fieldPath);
                    logger.debug("🔍 Trying getter method: {}", methodName);

                    return tryGetReference(resource, methodName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 Failed to resolve field path for '{}': {}", searchParam, e.getMessage());
        }
        
        // Fallback to hardcoded mappings for common cases
        if ("subject".equals(searchParam)) {
            return tryGetReference(resource, "getSubject");
        } else if ("patient".equals(searchParam)) {
            // Try direct patient field first
            String directPatient = tryGetReference(resource, "getPatient");
            if (directPatient != null) {
                return directPatient;
            }
            
            // For resources like Observation, the patient is in the "subject" field
            String subjectPatient = tryGetReference(resource, "getSubject");
            if (subjectPatient != null && subjectPatient.contains("Patient/")) {
                return subjectPatient;
            }
        }
        
        return null;
    }
    
    /**
     * Convert field path to getter method name
     * Examples: "medicationReference" -> "getMedicationReference"
     *           "subject" -> "getSubject"
     */
    private String fieldPathToGetterMethod(String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            return null;
        }
        
        // Handle nested paths by taking only the first part
        String[] parts = fieldPath.split("\\.");
        String fieldName = parts[0];
        
        // Convert to getter method name: capitalize first letter and prepend "get"
        return "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
    }
    
    /**
     * Helper method to try getting a reference using reflection
     */
    private String tryGetReference(Resource resource, String methodName) {
        try {
            java.lang.reflect.Method getMethod = resource.getClass().getMethod(methodName);
            Object referenceObj = getMethod.invoke(resource);
            if (referenceObj != null) {
                java.lang.reflect.Method getReference = referenceObj.getClass().getMethod("getReference");
                String reference = (String) getReference.invoke(referenceObj);
                logger.debug("🔍 Found reference via {}: {}", methodName, reference);
                return reference;
            } else {
                logger.warn("🔍 Method {} returned null reference object", methodName);
            }
        } catch (Exception e) {
            logger.error("🔍 No {} method found or error accessing it: {}", methodName, e.getMessage());
        }
        return null;
    }
    
    /**
     * Extract ID from a FHIR reference string
     */
    private String extractIdFromReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        
        // Handle both "Patient/123" and "123" formats
        int slashIndex = reference.lastIndexOf('/');
        if (slashIndex != -1 && slashIndex < reference.length() - 1) {
            return reference.substring(slashIndex + 1);
        }
        
        return reference;
    }
    
    /**
     * Get resources by their IDs (similar to getPrimaryResourcesByIds but more generic)
     */
    private List<Resource> getResourcesByIds(String resourceType, List<String> ids, String bucketName) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        
        logger.debug("🔍 Getting {} resources by IDs from bucket '{}': {}", resourceType, bucketName, ids);
        
        @SuppressWarnings("unchecked")
        Class<? extends Resource> resourceClassType = (Class<? extends Resource>) fhirContext.getResourceDefinition(resourceType).getImplementingClass();
        FhirResourceDaoImpl<?> dao = serviceFactory.getService(resourceClassType);
        
        try {
            // Use optimized bulk read instead of individual reads
            @SuppressWarnings("unchecked")
            List<Resource> resources = (List<Resource>) dao.readMultiple(resourceType, ids, bucketName);

            logger.debug("🔍 Successfully retrieved {}/{} {} resources", resources.size(), ids.size(), resourceType);
            return resources;
            
        } catch (Exception e) {
            logger.error("Failed to retrieve multiple {} resources: {}", resourceType, e.getMessage());
            throw new InvalidRequestException("Failed to retrieve included resources: " + e.getMessage());
        }
    }
    
    /**
     * Build bundle with only primary resources (no includes)
     */
    private Bundle buildBundleWithPrimaryResourcesOnly(List<Resource> primaryResources, String primaryResourceType,
                                                     SummaryEnum summaryMode, Set<String> elements) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(primaryResources.size());
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        return bundle;
    }
    
    /**
     * Handle pagination for chained searches
     */
    private Bundle handleChainPaginationInternal(SearchState searchState, String continuationToken, int offset, int count) {
        logger.debug("🔗 Handling chain pagination: offset={}, count={}", offset, count);
        
        // Execute primary chain search for next batch using stored referenced IDs
        List<Resource> primaryResources = executePrimaryChainSearch(
            searchState.getPrimaryResourceType(),
            searchState.getRevIncludeSearchParam(), // Reused field stores reference field path
            searchState.getRevIncludeResourceType(), // Reused field stores target resource type
            searchState.getPrimaryResourceIds(), // Stored referenced resource IDs
            searchState.getCachedFtsQueries(), // Additional search criteria
            count,
            searchState.getSortFields(),
            offset, // Add offset parameter
            searchState.getBucketName()
        );
        
        // Update search state
        searchState.setCurrentPrimaryOffset(offset + primaryResources.size());
        
        // Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(searchState.getTotalPrimaryResources());
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(searchState.getPrimaryResourceType() + "/" + resource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add next link if there are more results
        if (primaryResources.size() == count && searchState.getCurrentPrimaryOffset() < searchState.getTotalPrimaryResources()) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, searchState.getCurrentPrimaryOffset(), searchState.getPrimaryResourceType(), searchState.getBucketName(), searchState.getPageSize(), searchState.getBaseUrl()));
        }
        
        logger.info("🔗 Returning chain pagination: {} results", primaryResources.size());
        return bundle;
    }
    
    // ========== Chain Search Helper Methods ==========
    
    /**
     * Execute chain query to find referenced resource IDs
     */
    private List<String> executeChainQuery(String targetResourceType, String searchParam, 
                                         String searchValue, String bucketName) {
        logger.debug("🔗 Executing chain query: {} where {}={}", targetResourceType, searchParam, searchValue);
        
        // Build FTS query for the chain parameter
        Map<String, List<String>> chainCriteria = Map.of(searchParam, List.of(searchValue));
        SearchQueryResult chainQueryResult = buildSearchQueries(targetResourceType, chainCriteria);
        List<SearchQuery> chainQueries = chainQueryResult.getFtsQueries();
        
        // Execute FTS search to get referenced resource IDs
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(chainQueries, targetResourceType, null);
        return ftsResult.getDocumentKeys().stream()
                .map(key -> key.substring(key.lastIndexOf("/") + 1)) // Extract ID from "ResourceType/id"
                .collect(Collectors.toList());
    }
    
    /**
     * Execute primary search for resources that reference the found IDs
     */
    private List<Resource> executePrimaryChainSearch(String primaryResourceType, String referenceFieldPath,
                                                   String targetResourceType, List<String> referencedIds,
                                                   List<SearchQuery> additionalQueries, int count,
                                                   List<SearchSort> sortFields, String bucketName) {
        return executePrimaryChainSearch(primaryResourceType, referenceFieldPath, targetResourceType,
                                       referencedIds, additionalQueries, count, sortFields, 0, bucketName);
    }
    
    /**
     * Execute primary search for resources that reference the found IDs (with offset)
     */
    private List<Resource> executePrimaryChainSearch(String primaryResourceType, String referenceFieldPath,
                                                   String targetResourceType, List<String> referencedIds,
                                                   List<SearchQuery> additionalQueries, int count,
                                                   List<SearchSort> sortFields, int offset, String bucketName) {
        
        logger.debug("🔗 Executing primary chain search: {} where {} references {} IDs: {}", 
                   primaryResourceType, referenceFieldPath, targetResourceType, referencedIds);
        
        // Build FTS query for primary resources that reference the found IDs
        List<SearchQuery> primaryQueries = new ArrayList<>();
        
        // Add reference queries (similar to revinclude logic)
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String referencedId : referencedIds) {
            String referenceValue = targetResourceType + "/" + referencedId;
            referenceQueries.add(SearchQuery.match(referenceValue).field(referenceFieldPath));
        }
        
        if (!referenceQueries.isEmpty()) {
            primaryQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Add any additional search criteria
        if (additionalQueries != null) {
            primaryQueries.addAll(additionalQueries);
        }
        
        // Use new pagination strategy: get up to 1000 keys, then slice for requested page
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(primaryQueries, primaryResourceType, sortFields);
        List<String> allDocumentKeys = ftsResult.getDocumentKeys();
        
        // Get documents for the requested page (handle offset by slicing the keys)
        int fromIndex = Math.min(offset, allDocumentKeys.size());
        int toIndex = Math.min(offset + count, allDocumentKeys.size());
        List<String> pageKeys = fromIndex < toIndex ? allDocumentKeys.subList(fromIndex, toIndex) : List.of();
        
        List<Resource> results = ftsKvSearchService.getDocumentsFromKeys(pageKeys, primaryResourceType);
        logger.info("🔗 Primary chain search (new pagination) returned {} {} resources from {} total keys (offset: {})", 
                   results.size(), primaryResourceType, allDocumentKeys.size(), offset);
        return results;
    }
    
    /**
     * Get total count for chained searches
     */
    private int getTotalChainSearchCount(String primaryResourceType, String referenceFieldPath,
                                       String targetResourceType, List<String> referencedIds,
                                       List<SearchQuery> additionalQueries, String bucketName) {
        
        // Build count query for primary resources that reference the found IDs
        List<SearchQuery> primaryQueries = new ArrayList<>();
        
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String referencedId : referencedIds) {
            String referenceValue = targetResourceType + "/" + referencedId;
            referenceQueries.add(SearchQuery.match(referenceValue).field(referenceFieldPath));
        }
        
        if (!referenceQueries.isEmpty()) {
            primaryQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Add any additional search criteria
        if (additionalQueries != null) {
            primaryQueries.addAll(additionalQueries);
        }
        
        return getAccurateCount(primaryQueries, primaryResourceType, bucketName);
    }
}
