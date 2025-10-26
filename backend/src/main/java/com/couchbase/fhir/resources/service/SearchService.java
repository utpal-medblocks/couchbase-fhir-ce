package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.fhir.resources.interceptor.RequestPerfBagUtils;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.search.validation.FhirSearchValidationException;
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import com.couchbase.fhir.resources.search.SearchQueryResult;
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
    private static final int MAX_BUNDLE_SIZE = 500;  // Hard cap on total Bundle resources (Primary + Secondary)
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private FhirSearchParameterPreprocessor searchPreprocessor;
    
    @Autowired
    private SearchStateManager searchStateManager;
    
    @Autowired
    private FhirConfig fhirConfig;
    
    @Autowired
    private FtsKvSearchService ftsKvSearchService;
    
    @Autowired
    private FtsSearchService ftsSearchService;
    
    @Autowired
    private BatchKvService batchKvService;
    
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
        
        // Flatten parameters for processing
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));
        
        // Check for chained parameters FIRST (before validation)
        ChainParam chainParam = detectChainParameter(searchParams, resourceType);
        
        // Validate search parameters (excluding chain, _include, _revinclude - they have special syntax)
        Map<String, List<String>> paramsToValidate = new java.util.HashMap<>(allParams);
        
        // Remove chain parameter from validation since validator doesn't understand chain syntax
        if (chainParam != null) {
            paramsToValidate.remove(chainParam.getOriginalParameter());
            logger.debug("🔍 Detected chain parameter '{}', excluding from validation", chainParam.getOriginalParameter());
        }
        
        // Remove _include/_revinclude from validation since validator doesn't parse their syntax correctly
        // (e.g., Encounter:subject:Patient causes validator to see "subject:Patient" as a parameter name)
        paramsToValidate.remove("_include");
        paramsToValidate.remove("_revinclude");
        
        try {
            searchPreprocessor.validateSearchParameters(resourceType, paramsToValidate);
        } catch (FhirSearchValidationException e) {
            throw new InvalidRequestException(e.getUserFriendlyMessage());
        }
        
    // Parse FHIR standard parameters
    SummaryEnum summaryMode = parseSummaryParameter(searchParams);
    Set<String> elements = parseElementsParameter(searchParams);
    int count = parseCountParameter(searchParams);
    // Capture whether user explicitly provided _count BEFORE we strip control params
    boolean userExplicitCount = searchParams.containsKey("_count");
        List<SearchSort> sortFields = parseSortParameter(searchParams);
        String totalMode = parseTotalParameter(searchParams);
        
        // Check for _revinclude parameters (can be multiple) using HAPI's Include class
        List<Include> revIncludes = new ArrayList<>();
        List<String> revIncludeValues = allParams.get("_revinclude");
        if (revIncludeValues != null && !revIncludeValues.isEmpty()) {
            for (String revIncludeValue : revIncludeValues) {
                try {
                    Include revInclude = new Include(revIncludeValue, true); // true = reverse include
                    revIncludes.add(revInclude);
                    logger.debug("🔍 Parsed HAPI RevInclude: {}", revInclude);
                } catch (Exception e) {
                    throw new InvalidRequestException("Invalid _revinclude parameter: " + revIncludeValue + " - " + e.getMessage());
                }
            }
            logger.info("🔍 Total _revinclude parameters: {}", revIncludes.size());
        }
        
        // Check for _include parameters (can be multiple) using HAPI's Include class
        List<Include> includes = new ArrayList<>();
        List<String> includeValues = allParams.get("_include");
        if (includeValues != null && !includeValues.isEmpty()) {
            for (String includeValue : includeValues) {
                try {
                    Include include = new Include(includeValue);
                    includes.add(include);
                    logger.debug("🔍 Parsed HAPI Include: {}", include);
                } catch (Exception e) {
                    throw new InvalidRequestException("Invalid _include parameter: " + includeValue + " - " + e.getMessage());
                }
            }
            logger.info("🔍 Total _include parameters: {}", includes.size());
        }
        
    // Remove control parameters from search criteria (we already captured userExplicitCount)
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        searchParams.remove("_revinclude");
        searchParams.remove("_include");
        
        // Remove chain parameter from search criteria if found (both from searchParams and allParams)
        if (chainParam != null) {
            searchParams.remove(chainParam.getOriginalParameter());
            allParams.remove(chainParam.getOriginalParameter());
            logger.debug("🔍 Removed chain parameter '{}' from query building", chainParam.getOriginalParameter());
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
        
        // Check if this is a chained search (HIGHEST PRIORITY - most specific)
        if (chainParam != null) {
            Bundle result = handleChainSearch(resourceType, ftsQueries, chainParam, includes, count,
                                   sortFields, summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a _revinclude search (can have multiple _revinclude parameters)
        if (!revIncludes.isEmpty()) {
            Bundle result = handleMultipleRevIncludeSearch(resourceType, ftsQueries, revIncludes, count, 
                                        summaryMode, elements, totalMode, bucketName, requestDetails);
            RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
            RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
            return result;
        }
        
        // Check if this is a _include search (can have multiple _include parameters)
        if (!includes.isEmpty()) {
            try {
                logger.info("🔍 About to call handleMultipleIncludeSearch for {} with {} includes", resourceType, includes.size());
                Bundle result = handleMultipleIncludeSearch(resourceType, ftsQueries, includes, count, 
                                         summaryMode, elements, totalMode, bucketName, requestDetails);
                logger.info("🔍 handleMultipleIncludeSearch completed successfully");
                RequestPerfBagUtils.addTiming(requestDetails, "search_service", System.currentTimeMillis() - searchStartMs);
                RequestPerfBagUtils.addCount(requestDetails, "search_results", result.getTotal());
                return result;
            } catch (Exception e) {
                if (isNoActiveConnectionError(e)) {
                    logger.error("🔍 ❌ Include search failed: No active Couchbase connection");
                } else {
                    logger.error("🔍 ❌ Exception in handleMultipleIncludeSearch: {} - {}", e.getClass().getName(), e.getMessage());
                }
                throw e; // Re-throw to let HAPI handle it
            }
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
            // Trim whitespace (including newlines from URL encoding issues like %0A)
            int count = Integer.parseInt(countValue.trim());
            if (count <= 0) return DEFAULT_PAGE_SIZE;
            if (count > MAX_FTS_FETCH_SIZE) return MAX_FTS_FETCH_SIZE; // Max 1000
            return count;
        } catch (NumberFormatException e) {
            logger.warn("🔍 Invalid _count value '{}', using default {}", countValue, DEFAULT_PAGE_SIZE);
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
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("📦 Bundle page composition: match={}, include={}, totalEntries={}",
                    results.size(), 0, bundle.getEntry().size());
        }
        
        // Add next link if pagination is needed
        if (continuationToken != null && allDocumentKeys.size() > pageSize) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, pageSize, resourceType, bucketName, pageSize, baseUrl));
        }
        
        logger.info("🚀 New Pagination: Returning {} results, total: {}, pages: {}", 
                   results.size(), allDocumentKeys.size(), needsPagination ? "multiple" : "single");
        return bundle;
    }
    
    /**
     * Handle continuation token for pagination (supports BOTH legacy key-list and new query-based)
     */
    private Bundle handleContinuationTokenNewPagination(String continuationToken, String resourceType,
                                                       int offset, int count,
                                                       SummaryEnum summaryMode, Set<String> elements,
                                                       String bucketName, RequestDetails requestDetails) {
        
        logger.info("🔑 PAGINATION: Processing continuation token for {}, offset={}, count={}", 
                   resourceType, offset, count);
        
        // Retrieve pagination state (bucketName from method parameter)
        PaginationState paginationState = searchStateManager.getPaginationState(continuationToken, bucketName);
        logger.info("🔍 DEBUG: handleContinuationTokenNewPagination - state={}", paginationState != null ? "FOUND" : "NULL");
        
        if (paginationState == null) {
            logger.warn("❌ Pagination state not found or expired for token: {}", continuationToken);
            // Return 410 Gone for expired/invalid pagination token (FHIR standard)
            throw new ResourceGoneException("Pagination state has expired or is invalid. Please repeat your original search.");
        }
        
        logger.info("🔍 DEBUG: State details - useLegacy={}, searchType={}", 
                   paginationState.isUseLegacyKeyList(), paginationState.getSearchType());
        
        // Check if using NEW query-based approach or LEGACY key-list approach
        if (!paginationState.isUseLegacyKeyList()) {
            // NEW APPROACH: Re-execute FTS query with updated offset
            logger.info("🚀 NEW QUERY-BASED PAGINATION: Re-executing FTS for page offset={}", offset);
            logger.info("🔍 DEBUG: About to call handleQueryBasedPagination");
            return handleQueryBasedPagination(continuationToken, paginationState, offset, count, summaryMode, elements, requestDetails);
        }
        
        // LEGACY APPROACH: Use stored document keys
        logger.info("🔑 LEGACY KEY-LIST PAGINATION: Using stored keys");
        logger.info("🔍 DEBUG: About to call getAllDocumentKeys");
        List<String> allDocumentKeys = paginationState.getAllDocumentKeys();
        logger.info("🔍 DEBUG: getAllDocumentKeys returned: {}", allDocumentKeys != null ? "NOT NULL" : "NULL");
        int pageSize = count > 0 ? count : paginationState.getPageSize();
        int fromIndex = Math.min(offset, allDocumentKeys.size());
        int toIndex = Math.min(offset + pageSize, allDocumentKeys.size());
        List<String> currentPageKeys = fromIndex < toIndex ? allDocumentKeys.subList(fromIndex, toIndex) : List.of();
        if (currentPageKeys.isEmpty()) {
            logger.info("🔑 No more results for pagination token: {}", continuationToken);
            return createEmptyBundle();
        }
        
        logger.info("🔑 KV-Only: Fetching {} documents for page {}/{}", 
                   currentPageKeys.size(), paginationState.getCurrentPage(), paginationState.getTotalPages());
        
        // For mixed resource types (like _revinclude), we need to group keys by resource type
        // But preserve the original order (primary resources first, then secondary)
        List<Resource> results = new ArrayList<>();
        String searchType = paginationState.getSearchType();
        if ("revinclude".equals(searchType) || "include".equals(searchType)) {
            // Group keys by resource type and retrieve from appropriate collections
            Map<String, List<String>> keysByResourceType = currentPageKeys.stream()
                .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
            
            // Fetch resources grouped by type for efficiency
            Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
                String keyResourceType = entry.getKey();
                List<String> keysForType = entry.getValue();
                logger.debug("🔑 Retrieving {} {} documents", keysForType.size(), keyResourceType);
                List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
                // Store in map for ordering
                for (Resource resource : resourcesForType) {
                    String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                    resourcesByKey.put(resourceKey, resource);
                }
            }
            
            // Restore original order from currentPageKeys (primary first, then secondary)
            for (String key : currentPageKeys) {
                Resource resource = resourcesByKey.get(key);
                if (resource != null) {
                    results.add(resource);
                }
            }
        } else {
            // Regular pagination - all keys are same resource type
            results = ftsKvSearchService.getDocumentsFromKeys(currentPageKeys, resourceType);
        }
        
        // Note: We do NOT update currentOffset in Couchbase
        // The offset is tracked in the URL (_offset parameter), not in the document
        // This keeps the document immutable (write once, read many) and avoids resetting TTL
        
        // Calculate next offset for "next" link
        int nextOffset = offset + currentPageKeys.size();
        boolean hasMoreResults = nextOffset < allDocumentKeys.size();
        
        // Note: We rely on TTL for cleanup (no explicit delete to avoid unnecessary DB chatter)
        
        // Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // For revinclude/include searches, total should reflect ONLY primary matches, not included resources
        if ("revinclude".equals(searchType) || "include".equals(searchType)) {
            int primaryCount = paginationState.getPrimaryResourceCount();
            bundle.setTotal(primaryCount);
        } else {
            bundle.setTotal(allDocumentKeys.size()); // Total is known from initial FTS for pure primary searches
        }
        
        // Add resources to bundle with filtering
        String baseUrl = paginationState.getBaseUrl();
        String primaryResourceType = paginationState.getResourceType();
        for (Resource resource : results) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String actualResourceType = resource.getResourceType().name();
            
            // Determine search mode: primary resources are "match", secondary are "include"
            Bundle.SearchEntryMode searchMode = actualResourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + actualResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(searchMode);
        }
        if (logger.isDebugEnabled()) {
            int includeCount = (int) results.stream()
                    .filter(r -> !r.getResourceType().name().equals(primaryResourceType))
                    .count();
            int matchCount = results.size() - includeCount;
            logger.debug("📦 Bundle page composition (continuation): match={}, include={}, totalEntries={}",
                    matchCount, includeCount, bundle.getEntry().size());
        }
        
        // Add next link if more results available
        if (hasMoreResults) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, nextOffset, resourceType, bucketName, pageSize, baseUrl));
        }
        
        // Calculate current page for logging (1-based)
        int currentPage = (offset / pageSize) + 1;
        int totalPages = (int) Math.ceil((double) allDocumentKeys.size() / pageSize);
        
        logger.info("🔑 KV-Only Pagination: Returning {} results, page {}/{}, total(primaryOnly)={} (combinedKeys={})", 
                   results.size(), currentPage, totalPages,
                   ("revinclude".equals(searchType) || "include".equals(searchType)) ? paginationState.getPrimaryResourceCount() : allDocumentKeys.size(),
                   allDocumentKeys.size());
        return bundle;
    }
    
    /**
     * Handle query-based pagination (NEW approach) - Re-execute FTS query for each page.
     * This method supports _revinclude, _include, and regular searches.
     */
    private Bundle handleQueryBasedPagination(String continuationToken, PaginationState state, int offset, int count,
                                             SummaryEnum summaryMode, Set<String> elements,
                                             RequestDetails requestDetails) {
        logger.info("🔍 DEBUG: handleQueryBasedPagination ENTERED");
        logger.info("🔍 DEBUG: state={}, offset={}, count={}", state != null ? "EXISTS" : "NULL", offset, count);
        
        String searchType = state.getSearchType();
        String primaryResourceType = state.getResourceType();
        int pageSize = count > 0 ? count : state.getPrimaryPageSize();
        String bucketName = state.getBucketName();
        
        logger.info("🚀 QUERY-BASED PAGE: type={}, primaryType={}, offset={}, pageSize={}", 
                   searchType, primaryResourceType, offset, pageSize);
        
        // Step 1: Rebuild FTS queries from serialized JSON
        // TODO: For now, use simple approach - rebuild from stored criteria
        // Since we're storing empty query lists, we'll use matchAll for now
        List<SearchQuery> primaryQueries = new ArrayList<>();
        if (state.getPrimaryFtsQueriesJson() != null && !state.getPrimaryFtsQueriesJson().isEmpty()) {
            // TODO: Deserialize queries properly
            logger.warn("⚠️  Query deserialization not yet implemented, using matchAll");
        }
        // For now, use matchAll (works for initial testing)
        // primaryQueries is empty, will use matchAll in buildCombinedSearchQuery
        
        // Step 2: Re-execute FTS for primaries with updated offset
        List<SearchSort> sortFields = deserializeSortFields(state.getSortFieldsJson());
        
        FtsSearchService.FtsSearchResult primaryResult = ftsSearchService.searchForKeys(
            primaryQueries, primaryResourceType, offset, pageSize, sortFields);
        
        List<String> primaryKeys = primaryResult.getDocumentKeys();
        logger.info("🚀 FTS re-execution returned {} primary keys", primaryKeys.size());
        
        if (primaryKeys.isEmpty()) {
            logger.info("🚀 No more primaries at offset={}", offset);
            return createEmptyBundle();
        }
        
        // Step 3: Fetch primary resources
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(primaryKeys, primaryResourceType);
        
        // Step 4: Handle secondaries based on search type
        List<Resource> allResources = new ArrayList<>(primaryResources);
        
        if ("revinclude".equals(searchType)) {
            // Re-fetch secondaries for these primaries
            String revIncludeType = state.getRevIncludeResourceType();
            String revIncludeParam = state.getRevIncludeSearchParam();
            
            logger.info("🚀 Fetching _revinclude secondaries: {} references {}", revIncludeType, revIncludeParam);
            
            // Build reference queries
            List<SearchQuery> refQueries = new ArrayList<>();
            for (String primaryKey : primaryKeys) {
                refQueries.add(SearchQuery.match(primaryKey).field(revIncludeParam + ".reference"));
            }
            
            if (!refQueries.isEmpty()) {
                List<SearchQuery> secondaryQueries = List.of(
                    SearchQuery.disjuncts(refQueries.toArray(new SearchQuery[0]))
                );
                
                int maxSecondaries = state.getMaxBundleSize() - primaryKeys.size();
                FtsSearchService.FtsSearchResult secondaryResult = ftsSearchService.searchForKeys(
                    secondaryQueries, revIncludeType, 0, maxSecondaries, sortFields);
                
                List<String> secondaryKeys = secondaryResult.getDocumentKeys();
                logger.info("🚀 Fetched {} secondary keys", secondaryKeys.size());
                
                List<Resource> secondaryResources = ftsKvSearchService.getDocumentsFromKeys(secondaryKeys, revIncludeType);
                allResources.addAll(secondaryResources);
            }
            
        } else if ("include".equals(searchType)) {
            // TODO: Handle _include similar to _revinclude
            logger.warn("⚠️  _include pagination not yet fully implemented for query-based approach");
        }
        
        // Step 5: Build Bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) primaryResult.getTotalCount());  // Total primaries from FTS metadata
        
        String baseUrl = state.getBaseUrl();
        for (Resource resource : allResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resType = resource.getResourceType().name();
            boolean isPrimary = resType.equals(primaryResourceType);
            Bundle.SearchEntryMode mode = isPrimary ? Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(mode);
        }
        
        // Step 6: Add next link if more results
        int nextOffset = offset + primaryKeys.size();
        boolean hasMore = primaryResult.getTotalCount() > nextOffset;
        
        if (hasMore) {
            // Build next URL with continuation token
            String nextUrl = buildNextPageUrl(continuationToken, nextOffset, primaryResourceType, bucketName, pageSize, baseUrl);
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(nextUrl);
        }
        
        logger.info("🚀 QUERY-BASED PAGE COMPLETE: {} resources ({} primaries + {} secondaries), hasMore={}", 
                   allResources.size(), primaryResources.size(), allResources.size() - primaryResources.size(), hasMore);
        
        return bundle;
    }
    
    /**
     * Handle MULTIPLE _revinclude search parameters with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Secondaries fetched for EACH _revinclude parameter (respecting bundle cap)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleMultipleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                                List<Include> revIncludes, int count,
                                                SummaryEnum summaryMode, Set<String> elements,
                                                String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.info("🔍 NEW PAGINATION: Handling {} _revinclude parameters for {} (count={}, maxBundle={})", 
                   revIncludes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);
        
        // If only one _revinclude, delegate to single-revinclude handler
        if (revIncludes.size() == 1) {
            return handleRevIncludeSearch(primaryResourceType, ftsQueries, revIncludes.get(0), count, 
                                        summaryMode, elements, totalMode, bucketName, requestDetails);
        }
        
        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.info("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.info("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.info("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }
        
        // Step 3: Derive primary resource references from keys
        List<String> primaryResourceReferences = new ArrayList<>(firstPagePrimaryKeys.size());
        for (String key : firstPagePrimaryKeys) {
            int slashIdx = key.indexOf('/');
            if (slashIdx > 0 && slashIdx < key.length() - 1) {
                primaryResourceReferences.add(key);
            }
        }
        
        logger.debug("🔍 Derived {} primary references for secondary lookup", primaryResourceReferences.size());
        
        // Step 4: Fetch SECONDARIES for EACH _revinclude parameter (respecting bundle cap)
        List<String> allSecondaryKeys = new ArrayList<>();
        int currentBundleSize = firstPagePrimaryKeys.size();
        
        for (Include revInclude : revIncludes) {
            String revIncludeResourceType = revInclude.getParamType();
            String revIncludeSearchParam = revInclude.getParamName();
            
            int maxSecondariesForThisType = MAX_BUNDLE_SIZE - currentBundleSize;
            if (maxSecondariesForThisType <= 0) {
                logger.info("🔍 Bundle size cap reached ({}/{}), skipping remaining _revinclude: {}", 
                           currentBundleSize, MAX_BUNDLE_SIZE, revInclude.getValue());
                break;
            }
            
            logger.info("🔍 Fetching _revinclude: {} -> {} (max={}, currentBundle={})", 
                       primaryResourceType, revInclude.getValue(), maxSecondariesForThisType, currentBundleSize);
            
            // Build FTS query for this secondary type
            List<SearchQuery> referenceQueries = new ArrayList<>();
            for (String primaryReference : primaryResourceReferences) {
                referenceQueries.add(SearchQuery.match(primaryReference).field(revIncludeSearchParam + ".reference"));
            }
            
            if (!referenceQueries.isEmpty()) {
                List<SearchQuery> revIncludeQueries = List.of(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
                
                List<SearchSort> secondarySortFields = new ArrayList<>();
                secondarySortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
                
                FtsSearchService.FtsSearchResult secondaryFtsResult = ftsSearchService.searchForKeys(
                    revIncludeQueries, revIncludeResourceType, 0, maxSecondariesForThisType, secondarySortFields);
                List<String> secondaryKeys = secondaryFtsResult.getDocumentKeys();
                
                logger.info("🔍 SECONDARY FTS ({}) returned {} keys (limit={}, bundleSize={})", 
                           revIncludeResourceType, secondaryKeys.size(), maxSecondariesForThisType, 
                           currentBundleSize + secondaryKeys.size());
                
                allSecondaryKeys.addAll(secondaryKeys);
                currentBundleSize += secondaryKeys.size();
            }
        }
        
        logger.info("🔍 First page composition: {} primaries + {} secondaries (from {} _revinclude params) = {} total", 
                   firstPagePrimaryKeys.size(), allSecondaryKeys.size(), revIncludes.size(), currentBundleSize);
        
        // Step 5: KV Batch fetch for all resources
        List<String> firstPageKeys = new ArrayList<>();
        firstPageKeys.addAll(firstPagePrimaryKeys);
        firstPageKeys.addAll(allSecondaryKeys);
        
        Map<String, List<String>> keysByResourceType = firstPageKeys.stream()
            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
        
        Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String keyResourceType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 KV Batch fetching {} {} documents", keysForType.size(), keyResourceType);
            List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
            for (Resource resource : resourcesForType) {
                String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                resourcesByKey.put(resourceKey, resource);
            }
        }
        
        List<Resource> firstPageResources = new ArrayList<>();
        for (String key : firstPageKeys) {
            Resource resource = resourcesByKey.get(key);
            if (resource != null) {
                firstPageResources.add(resource);
            }
        }
        
        logger.info("🔍 KV Batch completed: {} resources fetched", firstPageResources.size());
        
        // Step 6: Create pagination state if needed (Store ALL _revinclude parameters)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            // For multiple _revinclude, store as comma-separated list
            List<String> revIncludeStrings = revIncludes.stream()
                .map(inc -> inc.getValue())
                .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .includeParamsList(revIncludeStrings)  // Store all _revinclude params
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ NEW PAGINATION STATE: token={}, strategy=query-based, revIncludes={}, primaryOffset={}, pageSize={}", 
                       continuationToken, revIncludeStrings.size(), count, count);
        }
        
        // Step 7: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) totalPrimaryCount);  // Total = primaries only
        
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : firstPageResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            
            Bundle.SearchEntryMode searchMode = resourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + resource.getIdElement().getIdPart())
                    .getSearch().setMode(searchMode);
        }
        
        // Add pagination links if needed
        if (needsPagination && continuationToken != null) {
            String nextUrl = baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken 
                           + "&_offset=" + count + "&_count=" + count;
            bundle.addLink().setRelation("next").setUrl(nextUrl);
        }
        
        logger.debug("📦 Bundle page composition (_revinclude first page): match={}, include={}, totalEntries={}", 
                    firstPagePrimaryKeys.size(), allSecondaryKeys.size(), bundle.getEntry().size());
        
        logger.info("🔍 NEW _revinclude COMPLETE: Bundle={} resources ({} primaries + {} secondaries from {} types), total={} primaries, pagination={}, bundleCap={}", 
                   bundle.getEntry().size(), firstPagePrimaryKeys.size(), allSecondaryKeys.size(), 
                   revIncludes.size(), bundle.getTotal(), needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);
        
        return bundle;
    }
    
    /**
     * Handle SINGLE _revinclude search with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Secondaries fetched per page (not paginated independently)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleRevIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                        Include revInclude, int count,
                                        SummaryEnum summaryMode, Set<String> elements,
                                        String totalMode, String bucketName, RequestDetails requestDetails) {
        
        logger.info("🔍 NEW PAGINATION: Handling _revinclude search: {} -> {} (count={}, maxBundle={})", 
                   primaryResourceType, revInclude.getValue(), count, MAX_BUNDLE_SIZE);
        
        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.info("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            // Got more than requested - truncate to count and signal pagination
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.info("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            // Got count or fewer - no pagination needed
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.info("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }
        
        // Step 3: Derive primary resource references from keys (no need to fetch full resources yet)
        List<String> primaryResourceReferences = new ArrayList<>(firstPagePrimaryKeys.size());
        for (String key : firstPagePrimaryKeys) {
            // Keys are already in ResourceType/id format (e.g., "Patient/uuid")
            int slashIdx = key.indexOf('/');
            if (slashIdx > 0 && slashIdx < key.length() - 1) {
                primaryResourceReferences.add(key);
            } else {
                logger.warn("🔍 Unexpected key format for revinclude: {}", key);
            }
        }
        
        logger.debug("🔍 Derived {} primary references for secondary lookup", primaryResourceReferences.size());
        
        // Step 4: Execute FTS query for SECONDARY resources that reference the first page primaries
        String revIncludeResourceType = revInclude.getParamType();  // e.g., "Observation" from "Observation:subject"
        String revIncludeSearchParam = revInclude.getParamName();   // e.g., "subject"
        
        // Build FTS query for secondaries
        List<SearchQuery> revIncludeQueries = new ArrayList<>();
        List<SearchQuery> referenceQueries = new ArrayList<>();
        for (String primaryReference : primaryResourceReferences) {
            referenceQueries.add(SearchQuery.match(primaryReference).field(revIncludeSearchParam + ".reference"));
        }
        if (!referenceQueries.isEmpty()) {
            revIncludeQueries.add(SearchQuery.disjuncts(referenceQueries.toArray(new SearchQuery[0])));
        }
        
        // Calculate max secondaries: Bundle cap - primaries
        int maxSecondaries = MAX_BUNDLE_SIZE - firstPagePrimaryKeys.size();
        
        // Fetch secondaries with limit
        List<SearchSort> secondarySortFields = new ArrayList<>();
        secondarySortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
        
        FtsSearchService.FtsSearchResult secondaryFtsResult = ftsSearchService.searchForKeys(
            revIncludeQueries, revIncludeResourceType, 0, maxSecondaries, secondarySortFields);
        List<String> secondaryKeys = secondaryFtsResult.getDocumentKeys();
        
        logger.info("🔍 SECONDARY FTS returned {} keys (limit={}, bundleSize={}+{}={})", 
                   secondaryKeys.size(), maxSecondaries, firstPagePrimaryKeys.size(), 
                   secondaryKeys.size(), firstPagePrimaryKeys.size() + secondaryKeys.size());
        
        // Step 5: Combine all keys for this page (primary + secondary)
        List<String> firstPageKeys = new ArrayList<>();
        firstPageKeys.addAll(firstPagePrimaryKeys);
        firstPageKeys.addAll(secondaryKeys);
        
        logger.info("🔍 First page composition: {} primaries + {} secondaries = {} total resources", 
                   firstPagePrimaryKeys.size(), secondaryKeys.size(), firstPageKeys.size());
        
        // Step 6: KV Batch fetch for all resources on this page (grouped by resource type for efficiency)
        Map<String, List<String>> keysByResourceType = firstPageKeys.stream()
            .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
        
        Map<String, Resource> resourcesByKey = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : keysByResourceType.entrySet()) {
            String keyResourceType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 KV Batch fetching {} {} documents", keysForType.size(), keyResourceType);
            List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, keyResourceType);
            for (Resource resource : resourcesForType) {
                String resourceKey = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
                resourcesByKey.put(resourceKey, resource);
            }
        }
        
        // Restore original order (primary first, then secondary)
        List<Resource> firstPageResources = new ArrayList<>();
        for (String key : firstPageKeys) {
            Resource resource = resourcesByKey.get(key);
            if (resource != null) {
                firstPageResources.add(resource);
            }
        }
        
        logger.info("🔍 KV Batch completed: {} resources fetched", firstPageResources.size());
        
        // Step 7: Create pagination state if needed (NEW: Store FTS query, not keys!)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            // Serialize FTS queries for storage
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            
            PaginationState paginationState = PaginationState.builder()
                .searchType("revinclude")
                .resourceType(primaryResourceType)
                .primaryFtsQueriesJson(serializedQueries)
                .primaryOffset(count)  // Next page starts at offset=count
                .primaryPageSize(count)
                .sortFieldsJson(serializedSortFields)
                .maxBundleSize(MAX_BUNDLE_SIZE)
                .revIncludeResourceType(revIncludeResourceType)
                .revIncludeSearchParam(revIncludeSearchParam)
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .useLegacyKeyList(false)  // NEW query-based approach
                .build();
                
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ NEW PAGINATION STATE: token={}, strategy=query-based, primaryOffset={}, pageSize={}, maxBundle={}", 
                       continuationToken, count, count, MAX_BUNDLE_SIZE);
        }
        
        // Step 8: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // Total must reflect ONLY primary matches (FHIR spec) - use accurate FTS count
        bundle.setTotal((int) totalPrimaryCount);
        
        // Add resources to bundle with appropriate search mode
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        for (Resource resource : firstPageResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            
            // Determine if this is a primary or secondary resource
            Bundle.SearchEntryMode searchMode = resourceType.equals(primaryResourceType) ? 
                Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(searchMode);
        }
        if (logger.isDebugEnabled()) {
            int pagePrimaryCount = Math.min(allPrimaryKeys.size(), firstPageKeys.size());
            int pageIncludeCount = firstPageKeys.size() - pagePrimaryCount;
            logger.debug("📦 Bundle page composition (_revinclude first page): match={}, include={}, totalEntries={}",
                    pagePrimaryCount, pageIncludeCount, bundle.getEntry().size());
        }
        
        // Add next link if pagination is needed
        if (continuationToken != null && needsPagination) {
            String baseUrlFinal = extractBaseUrl(requestDetails, bucketName);
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count, primaryResourceType, bucketName, count, baseUrlFinal));
        }
        
        logger.info("🔍 NEW _revinclude COMPLETE: Bundle={} resources ({} primaries + {} secondaries), " +
                   "total={} primaries, pagination={}, bundleCap={}", 
                   firstPageResources.size(), firstPagePrimaryKeys.size(), secondaryKeys.size(),
                   totalPrimaryCount, needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);
        return bundle;
    }
    
    /**
     * Handle multiple _include searches with FHIR-compliant count+1 pagination strategy.
     * 
     * Key Changes:
     * - _count applies to PRIMARY resources only (FHIR spec compliance)
     * - Fetch count+1 primaries to detect pagination need
     * - Extract references from ONLY those primaries (not all 1000!)
     * - Fetch included resources (up to bundle limit)
     * - Bundle capped at MAX_BUNDLE_SIZE (500) total resources
     * - Store FTS query (not keys) for lightweight pagination state
     */
    private Bundle handleMultipleIncludeSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                              List<Include> includes, int count,
                                              SummaryEnum summaryMode, Set<String> elements,
                                              String totalMode, String bucketName, RequestDetails requestDetails) {
        logger.info("🔍 NEW PAGINATION: Handling {} _include parameters for {} (count={}, maxBundle={})", 
                   includes.size(), primaryResourceType, count, MAX_BUNDLE_SIZE);

        // Step 1: FTS query for PRIMARY resources with size=count+1 (to detect pagination need)
        List<SearchSort> sortFields = new ArrayList<>();
        sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));  // Default sort
        
        FtsSearchService.FtsSearchResult primaryFtsResult = ftsSearchService.searchForKeys(
            ftsQueries, primaryResourceType, 0, count + 1, sortFields);  // Fetch count+1
        
        List<String> allPrimaryKeys = primaryFtsResult.getDocumentKeys();
        long totalPrimaryCount = primaryFtsResult.getTotalCount();  // Accurate count from FTS metadata
        
        if (allPrimaryKeys.isEmpty()) {
            logger.info("🔍 No primary resources found, returning empty bundle");
            return createEmptyBundle();
        }
        
        logger.info("🔍 PRIMARY FTS returned {} keys (requested count+1={}, total={})", 
                   allPrimaryKeys.size(), count + 1, totalPrimaryCount);
        
        // Step 2: Detect pagination need (did we get count+1 results?)
        boolean needsPagination = allPrimaryKeys.size() > count;
        List<String> firstPagePrimaryKeys;
        
        if (needsPagination) {
            // Got more than requested - truncate to count and signal pagination
            firstPagePrimaryKeys = allPrimaryKeys.subList(0, count);
            logger.info("🔍 PAGINATION NEEDED: Got {} primaries, truncating to {} for first page", 
                       allPrimaryKeys.size(), count);
        } else {
            // Got count or fewer - no pagination needed
            firstPagePrimaryKeys = allPrimaryKeys;
            logger.info("🔍 NO PAGINATION: Got {} primaries (≤ count={})", allPrimaryKeys.size(), count);
        }

        // Step 3: Fetch ONLY the first page primary resources (optimization!)
        long primFetchStart = System.currentTimeMillis();
        List<Resource> firstPagePrimaryResources = ftsKvSearchService.getDocumentsFromKeys(firstPagePrimaryKeys, primaryResourceType);
        logger.info("🔍 Fetched {} primary resources in {} ms for first page", 
                firstPagePrimaryResources.size(), System.currentTimeMillis() - primFetchStart);

        // Step 4: Extract _include references from ONLY first page primaries (not all 1000!)
        java.util.LinkedHashSet<String> includeKeySet = new java.util.LinkedHashSet<>();
        int includeParamIndex = 0;
        
        for (Include include : includes) {
            includeParamIndex++;
            String includeValue = include.getValue();
            logger.info("🔍 Processing _include[{}]: {}", includeParamIndex, includeValue);

            List<String> includeReferences = extractReferencesFromResources(firstPagePrimaryResources, include.getParamName());
            if (includeReferences.isEmpty()) {
                logger.warn("🔍 No references found for _include '{}'", includeValue);
                continue;
            }

            // Collect unique reference keys
            int before = includeKeySet.size();
            for (String ref : includeReferences) {
                if (ref != null && ref.contains("/")) {
                    includeKeySet.add(ref);
                }
            }
            int added = includeKeySet.size() - before;
            logger.info("🔍 _include '{}' produced {} raw refs, {} unique added (cumulative includeKeys={})", 
                    includeValue, includeReferences.size(), added, includeKeySet.size());
        }
        
        // Calculate max includes: Bundle cap - primaries
        int maxIncludes = MAX_BUNDLE_SIZE - firstPagePrimaryKeys.size();
        List<String> includeKeys = new ArrayList<>(includeKeySet);
        
        // Truncate if necessary
        if (includeKeys.size() > maxIncludes) {
            logger.warn("🔍 Truncating includes from {} to {} (bundle cap)", includeKeys.size(), maxIncludes);
            includeKeys = includeKeys.subList(0, maxIncludes);
        }

        logger.info("🔍 _include extraction complete: {} unique include keys (limit={})", 
                   includeKeys.size(), maxIncludes);
        
        // Step 5: Fetch include resources (grouped by resource type)
        Map<String, List<String>> includeKeysByType = includeKeys.stream()
                .collect(Collectors.groupingBy(k -> k.substring(0, k.indexOf('/'))));
        
        Map<String, Resource> includeResourcesByKey = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
            String includeType = entry.getKey();
            List<String> keysForType = entry.getValue();
            logger.debug("🔍 KV Batch fetching {} {} includes", keysForType.size(), includeType);
            List<Resource> fetched = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeType);
            for (Resource r : fetched) {
                String k = r.getResourceType().name() + "/" + r.getIdElement().getIdPart();
                includeResourcesByKey.put(k, r);
            }
        }
        
        logger.info("🔍 Fetched {} include resources", includeResourcesByKey.size());
        
        // Step 6: Combine all resources for this page (primary + includes)
        List<Resource> firstPageResources = new ArrayList<>();
        firstPageResources.addAll(firstPagePrimaryResources);  // Primaries first
        firstPageResources.addAll(includeResourcesByKey.values());  // Then includes
        
        logger.info("🔍 First page composition: {} primaries + {} includes = {} total resources", 
                   firstPagePrimaryResources.size(), includeResourcesByKey.size(), firstPageResources.size());

        // Step 7: Create pagination state if needed (NEW: Store FTS query, not keys!)
        String continuationToken = null;
        if (needsPagination) {
            String baseUrl = extractBaseUrl(requestDetails, bucketName);
            
            // Serialize FTS queries and _include parameters for storage
            List<String> serializedQueries = serializeFtsQueries(ftsQueries);
            List<String> serializedSortFields = serializeSortFields(sortFields);
            List<String> includeParamsList = includes.stream()
                    .map(Include::getValue)
                    .collect(Collectors.toList());
            
            PaginationState paginationState = PaginationState.builder()
                    .searchType("include")
                    .resourceType(primaryResourceType)
                    .primaryFtsQueriesJson(serializedQueries)
                    .primaryOffset(count)  // Next page starts at offset=count
                    .primaryPageSize(count)
                    .sortFieldsJson(serializedSortFields)
                    .maxBundleSize(MAX_BUNDLE_SIZE)
                    .includeParamsList(includeParamsList)  // Store _include parameters
                    .bucketName(bucketName)
                    .baseUrl(baseUrl)
                    .useLegacyKeyList(false)  // NEW query-based approach
                    .build();
                    
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ NEW PAGINATION STATE: token={}, strategy=query-based, primaryOffset={}, pageSize={}, includes={}, maxBundle={}",
                    continuationToken, count, count, includes.size(), MAX_BUNDLE_SIZE);
        }

        // Step 8: Build Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // Total must reflect ONLY primary matches (FHIR spec) - use accurate FTS count
        bundle.setTotal((int) totalPrimaryCount);

        // Add resources to bundle with appropriate search mode
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        int primaryCount = firstPagePrimaryResources.size();
        
        for (int i = 0; i < firstPageResources.size(); i++) {
            Resource resource = firstPageResources.get(i);
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resType = resource.getResourceType().name();
            
            // First N resources are primaries, rest are includes
            boolean isPrimary = (i < primaryCount) && resType.equals(primaryResourceType);
            Bundle.SearchEntryMode mode = isPrimary ? Bundle.SearchEntryMode.MATCH : Bundle.SearchEntryMode.INCLUDE;
            
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(mode);
        }

        // Add next link if pagination is needed
        if (continuationToken != null && needsPagination) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(buildNextPageUrl(continuationToken, count, primaryResourceType, bucketName, count, baseUrl));
        }

        logger.info("🔍 NEW _include COMPLETE: Bundle={} resources ({} primaries + {} includes), " +
                   "total={} primaries, pagination={}, bundleCap={}", 
                   firstPageResources.size(), firstPagePrimaryResources.size(), includeResourcesByKey.size(),
                   totalPrimaryCount, needsPagination ? "YES" : "NO", MAX_BUNDLE_SIZE);

        return bundle;
    }
    

    
    /**
     * Handle chained search with two-query strategy, optionally with _include parameters
     */
    private Bundle handleChainSearch(String primaryResourceType, List<SearchQuery> ftsQueries,
                                   ChainParam chainParam, List<Include> includes, int count, List<SearchSort> sortFields,
                                   SummaryEnum summaryMode, Set<String> elements,
                                   String totalMode, String bucketName, RequestDetails requestDetails) {

        logger.debug("🔗 Handling chained search: {} with chain {} and {} includes", 
                    primaryResourceType, chainParam, includes.size());

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
        
        // Step 2: Execute primary search to get ALL document keys (new pagination strategy)
        // Fetch up to 1000 keys upfront for consistent pagination (same as _revinclude)
        List<String> allDocumentKeys = executePrimaryChainSearchForKeys(
            primaryResourceType,
            chainParam.getReferenceFieldPath(),
            chainParam.getTargetResourceType(),
            referencedResourceIds,
            ftsQueries, // Additional search criteria
            sortFields,
            bucketName
        );
        
        logger.debug("🔗 Chain search found {} total document keys", allDocumentKeys.size());
        
        // Step 3: Fetch first page of resources via KV
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        int effectiveCount = Math.min(count, allDocumentKeys.size());
        List<String> firstPageKeys = allDocumentKeys.subList(0, effectiveCount);
        List<Resource> primaryResources = ftsKvSearchService.getDocumentsFromKeys(firstPageKeys, primaryResourceType);
        
        // Step 4: Process _include parameters (if any)
        List<Resource> includedResources = new ArrayList<>();
        if (!includes.isEmpty()) {
            logger.debug("🔗 Processing {} _include parameters for chain search", includes.size());
            
            // Extract references from primary resources for each include
            Set<String> includeKeys = new LinkedHashSet<>();
            for (Include include : includes) {
                String includeValue = include.getValue();
                logger.debug("🔗 Processing _include: {}", includeValue);
                
                List<String> refs = extractReferencesFromResources(primaryResources, include.getParamName());
                if (refs.isEmpty()) {
                    logger.warn("🔗 No references found for _include '{}'", includeValue);
                    continue;
                }
                
                includeKeys.addAll(refs);
                logger.info("🔗 _include '{}' produced {} refs (cumulative includeKeys={})", 
                           includeValue, refs.size(), includeKeys.size());
            }
            
            // Fetch included resources
            if (!includeKeys.isEmpty()) {
                logger.debug("🔗 KV Batch fetching {} includes", includeKeys.size());
                Map<String, List<String>> includeKeysByType = includeKeys.stream()
                    .collect(Collectors.groupingBy(key -> key.substring(0, key.indexOf("/"))));
                
                for (Map.Entry<String, List<String>> entry : includeKeysByType.entrySet()) {
                    String includeResourceType = entry.getKey();
                    List<String> keysForType = entry.getValue();
                    List<Resource> resourcesForType = ftsKvSearchService.getDocumentsFromKeys(keysForType, includeResourceType);
                    includedResources.addAll(resourcesForType);
                }
                
                logger.info("🔗 Fetched {} included resources", includedResources.size());
            }
        }
        
        // Step 5: Create pagination state (new Couchbase-backed strategy)
        String continuationToken = null;
        
        if (allDocumentKeys.size() > effectiveCount) {
            // Need pagination - store state in Couchbase Admin.cache
            PaginationState paginationState = PaginationState.builder()
                .searchType("chain")
                .resourceType(primaryResourceType)
                .allDocumentKeys(allDocumentKeys)
                .pageSize(count)
                .currentOffset(effectiveCount) // Next page starts after first page
                .bucketName(bucketName)
                .baseUrl(baseUrl)
                .primaryResourceCount(0) // Not applicable for chain searches
                .build();
            
            continuationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ Created chain PaginationState: token={}, totalKeys={}, pages={}", 
                       continuationToken, allDocumentKeys.size(), paginationState.getTotalPages());
        }
        
        // Step 6: Build response bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(allDocumentKeys.size());
        
        // Add primary resources (search mode = "match")
        for (Resource resource : primaryResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + primaryResourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.MATCH);
        }
        
        // Add included resources (search mode = "include")
        for (Resource resource : includedResources) {
            Resource filteredResource = applyResourceFiltering(resource, summaryMode, elements);
            String resourceType = resource.getResourceType().name();
            bundle.addEntry()
                    .setResource(filteredResource)
                    .setFullUrl(baseUrl + "/" + resourceType + "/" + filteredResource.getIdElement().getIdPart())
                    .getSearch()
                    .setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add self link
        bundle.addLink()
            .setRelation("self")
            .setUrl(baseUrl + "/" + primaryResourceType + "?" + chainParam.getOriginalParameter() + "=" + chainParam.getValue());
        
        // Add next link if there are more results
        if (continuationToken != null) {
            bundle.addLink()
                    .setRelation("next")
                    .setUrl(baseUrl + "/" + primaryResourceType + "?_page=" + continuationToken + "&_offset=" + effectiveCount + "&_count=" + count);
        }
        
        logger.info("🔗 Chained search complete: {} primaries + {} includes = {} total resources in bundle, totalPrimaries={}", 
                   primaryResources.size(), includedResources.size(), 
                   bundle.getEntry().size(), allDocumentKeys.size());
        return bundle;
    }
    
    /**
     * Handle pagination requests for both legacy and new pagination strategies
     */
    public Bundle handleRevIncludePagination(String continuationToken, int offset, int count) {
        logger.info("🔍 DEBUG: handleRevIncludePagination called - token={}, offset={}, count={}", continuationToken, offset, count);
        
        // Get current bucket from tenant context
        String bucketName = com.couchbase.fhir.resources.config.TenantContextHolder.getTenantId();
        logger.info("🔍 DEBUG: Bucket name: {}", bucketName);
        
        // First, try the new pagination strategy
        logger.info("🔍 DEBUG: About to call getPaginationState");
        PaginationState paginationState = null;
        try {
            paginationState = searchStateManager.getPaginationState(continuationToken, bucketName);
            logger.info("🔍 DEBUG: getPaginationState returned successfully");
        } catch (Exception e) {
            logger.error("🔍 DEBUG: Exception in getPaginationState: {}", e.getMessage(), e);
            throw e;
        }
        logger.info("🔍 DEBUG: Retrieved pagination state: {}", paginationState != null ? "FOUND" : "NULL");
        
        if (paginationState != null) {
            logger.info("🔍 DEBUG: paginationState is not null, checking fields...");
            try {
                String type = paginationState.getSearchType();
                logger.info("🔍 DEBUG: getSearchType() returned: {}", type);
                
                boolean useLegacy = paginationState.isUseLegacyKeyList();
                logger.info("🔍 DEBUG: isUseLegacyKeyList() returned: {}", useLegacy);
                
                String resType = paginationState.getResourceType();
                logger.info("🔍 DEBUG: getResourceType() returned: {}", resType);
                
                List<String> allKeys = paginationState.getAllDocumentKeys();
                logger.info("🔍 DEBUG: getAllDocumentKeys() returned: {}", allKeys != null ? "NOT NULL" : "NULL");
                
                logger.info("🔍 DEBUG: PaginationState details - type={}, useLegacy={}, resourceType={}", 
                           type, useLegacy, resType);
            } catch (Exception e) {
                logger.error("🔍 DEBUG: Exception accessing paginationState fields: {}", e.getMessage(), e);
                throw e;
            }
            
            logger.info("🔑 Using new pagination strategy for token: {}", continuationToken);
            logger.info("🔍 DEBUG: About to call handleContinuationTokenNewPagination");
            // Pass offset and count from URL to handle pagination (document is immutable)
            try {
                Bundle result = handleContinuationTokenNewPagination(continuationToken, paginationState.getResourceType(),
                                                           offset, count,
                                                           SummaryEnum.FALSE, null, // TODO: Store these in PaginationState if needed
                                                           paginationState.getBucketName(), null); // TODO: Store RequestDetails if needed
                logger.info("🔍 DEBUG: handleContinuationTokenNewPagination returned successfully");
                return result;
            } catch (Exception e) {
                logger.error("🔍 DEBUG: Exception in handleContinuationTokenNewPagination: {}", e.getMessage(), e);
                throw e;
            }
        }
        
        // No pagination state found - neither new nor legacy
        logger.error("❌ No pagination state found for token: {}", continuationToken);
        throw new ResourceGoneException("Pagination state has expired or is invalid. Please repeat your original search.");
    }
    
    
    // Helper methods for _revinclude implementation
    
    /**
     * Extract full resource references from a list of resources
     */
    private Bundle createEmptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        return bundle;
    }

    /**
     * Detect a missing Couchbase connection by walking the exception cause chain.
     * We use this to suppress noisy stack traces when the cluster has disconnected under load.
     */
    private boolean isNoActiveConnectionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("No active connection found")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
    
    /**
     * Extract full reference strings from resources (for heterogeneous _include)
     */
    private List<String> extractReferencesFromResources(List<Resource> resources, String searchParam) {
        List<String> allReferences = new ArrayList<>();
        
        for (Resource resource : resources) {
            List<String> references = extractReferencesFromResource(resource, searchParam);
            allReferences.addAll(references);
        }
        
        logger.debug("🔍 Extracted {} total reference strings from {} resources", allReferences.size(), resources.size());
        return allReferences;
    }
    
    /**
     * Get resources by their full reference strings (e.g., "Patient/123", "Observation/456")
     * Groups by resource type and does batch KV lookups for each type
     */
    // Removed unused helper methods extractResourceReferences / getResourcesByReferences as include logic now
    // derives references directly and fetches only the subset needed per page.
    
    /**
     * Extract reference values from a resource using the search parameter (handles lists)
     */
    private List<String> extractReferencesFromResource(Resource resource, String searchParam) {
        List<String> references = new ArrayList<>();
        
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
                    
                    // Navigate through the path and extract all references
                    references.addAll(navigatePathAndExtractReferences(resource, fieldPath));
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 Failed to resolve field path for '{}': {}", searchParam, e.getMessage());
        }
        
        // Log if fallback would be needed (for monitoring purposes)
        if (references.isEmpty()) {
            logger.info("🔍 No references found for search param '{}' on resource type '{}' - consider updating field mappings", 
                       searchParam, resource.getResourceType());
        }
        
        return references;
    }
    
    /**
     * Navigate through a field path and extract all reference values
     * Handles nested paths like "entry.item" where entry is a list
     */
    private List<String> navigatePathAndExtractReferences(Object currentObject, String fieldPath) {
        List<String> references = new ArrayList<>();
        
        if (currentObject == null || fieldPath == null || fieldPath.isEmpty()) {
            return references;
        }
        
        logger.debug("🔍 Navigating path '{}' on object type {}", fieldPath, currentObject.getClass().getSimpleName());
        
        // Split the path into parts
        String[] pathParts = fieldPath.split("\\.");
        List<Object> currentObjects = new ArrayList<>();
        currentObjects.add(currentObject);
        
        // Navigate through each part of the path
        for (int i = 0; i < pathParts.length; i++) {
            String part = pathParts[i];
            List<Object> nextObjects = new ArrayList<>();
            
            logger.debug("🔍 Processing path part {}/{}: '{}'", i + 1, pathParts.length, part);
            
            for (Object obj : currentObjects) {
                try {
                    // Convert field name to getter method name
                    String getterName = "get" + part.substring(0, 1).toUpperCase() + part.substring(1);
                    // logger.debug("🔍 Calling {} on {}", getterName, obj.getClass().getSimpleName());
                    
                    java.lang.reflect.Method getter = obj.getClass().getMethod(getterName);
                    Object result = getter.invoke(obj);
                    
                    if (result != null) {
                        // Check if result is a list/collection
                        if (result instanceof java.util.List) {
                            java.util.List<?> list = (java.util.List<?>) result;
                            // logger.debug("🔍 Got list with {} items", list.size());
                            nextObjects.addAll(list);
                        } else {
                            // logger.debug("🔍 Got single object: {}", result.getClass().getSimpleName());
                            nextObjects.add(result);
                        }
                    } else {
                        logger.debug("🔍 Getter returned null");
                    }
                } catch (Exception e) {
                    logger.debug("🔍 Error calling getter for '{}': {}", part, e.getMessage());
                }
            }
            
            currentObjects = nextObjects;
            
            if (currentObjects.isEmpty()) {
                logger.debug("🔍 No objects left to process after part '{}'", part);
                break;
            }
        }
        
        // Now extract references from the final objects
        logger.debug("🔍 Have {} final objects to extract references from", currentObjects.size());
        for (Object obj : currentObjects) {
            try {
                // Try to get the reference value - the object should be a Reference type
                if (obj instanceof org.hl7.fhir.r4.model.Reference) {
                    org.hl7.fhir.r4.model.Reference ref = (org.hl7.fhir.r4.model.Reference) obj;
                    String refValue = ref.getReference();
                    if (refValue != null) {
                        // logger.debug("🔍 Extracted reference: {}", refValue);
                        references.add(refValue);
                    }
                } else {
                    logger.debug("🔍 Final object is not a Reference: {}", obj.getClass().getSimpleName());
                }
            } catch (Exception e) {
                logger.debug("🔍 Error extracting reference from {}: {}", obj.getClass().getSimpleName(), e.getMessage());
            }
        }
        
        logger.debug("🔍 Total references extracted: {}", references.size());
        return references;
    }
    
    /**
     * Get resources by their IDs using optimized async batch KV retrieval
     */
    // Removed unused helper getResourcesByIds (replaced by direct getDocumentsFromKeys calls)
    

    
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
     * Execute primary chain search to get ALL document keys (new pagination strategy)
     * Returns up to 1000 document keys for Couchbase-backed pagination
     */
    private List<String> executePrimaryChainSearchForKeys(String primaryResourceType, String referenceFieldPath,
                                                          String targetResourceType, List<String> referencedIds,
                                                          List<SearchQuery> additionalQueries,
                                                          List<SearchSort> sortFields, String bucketName) {
        
        logger.debug("🔗 Executing primary chain search for keys: {} where {} references {} IDs", 
                   primaryResourceType, referenceFieldPath, targetResourceType);
        
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
        
        // Get up to 1000 keys for pagination
        FtsSearchService.FtsSearchResult ftsResult = ftsKvSearchService.searchForAllKeys(primaryQueries, primaryResourceType, sortFields);
        List<String> allDocumentKeys = ftsResult.getDocumentKeys();
        
        logger.info("🔗 Primary chain search found {} document keys", allDocumentKeys.size());
        return allDocumentKeys;
    }
    
    // ========== FTS Query Serialization Helpers ==========
    
    /**
     * Serialize FTS queries to JSON strings for storage in PaginationState.
     * 
     * @param ftsQueries List of SearchQuery objects
     * @return List of JSON strings (one per query)
     */
    private List<String> serializeFtsQueries(List<SearchQuery> ftsQueries) {
        if (ftsQueries == null || ftsQueries.isEmpty()) {
            return List.of();
        }
        
        List<String> serialized = new ArrayList<>();
        for (SearchQuery query : ftsQueries) {
            try {
                String json = query.export().toString();
                serialized.add(json);
            } catch (Exception e) {
                logger.error("❌ Failed to serialize FTS query: {}", e.getMessage());
                throw new RuntimeException("Failed to serialize FTS query: " + e.getMessage(), e);
            }
        }
        
        return serialized;
    }
    
    /**
     * Deserialize FTS queries from JSON strings stored in PaginationState.
     * 
     * @param queriesJson List of JSON strings
     * @return List of SearchQuery objects
     */
    private List<SearchQuery> deserializeFtsQueries(List<String> queriesJson) {
        if (queriesJson == null || queriesJson.isEmpty()) {
            return List.of();
        }
        
        List<SearchQuery> queries = new ArrayList<>();
        for (String json : queriesJson) {
            try {
                // Parse JSON and rebuild SearchQuery
                // Note: This is a simplification - may need more robust parsing
                SearchQuery query = parseSearchQueryFromJson(json);
                queries.add(query);
            } catch (Exception e) {
                logger.error("❌ Failed to deserialize FTS query from JSON: {}", e.getMessage());
                throw new RuntimeException("Failed to deserialize FTS query: " + e.getMessage(), e);
            }
        }
        
        return queries;
    }
    
    /**
     * Parse SearchQuery from JSON string.
     * This is a temporary implementation - may need to be enhanced based on query complexity.
     */
    private SearchQuery parseSearchQueryFromJson(String json) {
        // For now, we'll store the queries as-is and rebuild them from criteria
        // TODO: Implement proper JSON -> SearchQuery conversion if needed
        // Alternative: Store search criteria instead of serialized queries
        throw new UnsupportedOperationException("Query deserialization not yet implemented - will store criteria instead");
    }
    
    /**
     * Serialize sort fields to simple strings for storage in PaginationState.
     * Format: "fieldName:desc" or "fieldName:asc"
     * 
     * @param sortFields List of SearchSort objects
     * @return List of serialized sort strings
     */
    private List<String> serializeSortFields(List<SearchSort> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return List.of();
        }
        
        // For now, just store default sort indication
        // TODO: Implement proper sort field serialization if complex sorting is needed
        List<String> serialized = new ArrayList<>();
        serialized.add("meta.lastUpdated:desc");  // Default sort
        return serialized;
    }
    
    /**
     * Deserialize sort fields from JSON strings stored in PaginationState.
     * 
     * @param sortFieldsJson List of JSON strings
     * @return List of SearchSort objects
     */
    private List<SearchSort> deserializeSortFields(List<String> sortFieldsJson) {
        if (sortFieldsJson == null || sortFieldsJson.isEmpty()) {
            // Return default sort
            List<SearchSort> defaultSort = new ArrayList<>();
            defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
            return defaultSort;
        }
        
        // TODO: Implement proper JSON -> SearchSort conversion
        // For now, return default sort as fallback
        List<SearchSort> defaultSort = new ArrayList<>();
        defaultSort.add(SearchSort.byField("meta.lastUpdated").desc(true));
        return defaultSort;
    }
}

