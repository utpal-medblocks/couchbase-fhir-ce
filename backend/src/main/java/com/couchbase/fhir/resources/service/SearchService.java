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
    
    /**
     * Lightweight resolution for conditional operations.
     * Queries live collection only (excludes tombstones), projects ID only, LIMIT 2 for fast ambiguity detection.
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
        
        // Build lightweight ID-only query
        try {
            List<SearchQuery> ftsQueries = buildSearchQueries(resourceType, criteria);
            // Build ID-only query with LIMIT 2
            String query = buildResolveOneQuery(ftsQueries, resourceType, bucketName);
            
            logger.debug("🔍 Resolve query: {}", query);
            
            // Execute query
            Cluster cluster = connectionService.getConnection("default");
            QueryResult result = cluster.query(query);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            logger.info("🔍 SearchService.resolveOne: Found {} matches for {}", rows.size(), resourceType);
            
            // Analyze results
            if (rows.isEmpty()) {
                return ResolveResult.zero();
            } else if (rows.size() == 1) {
                String resourceId = extractResourceId(rows.get(0));
                logger.info("🔍 SearchService.resolveOne: Single match found: {}", resourceId);
                return ResolveResult.one(resourceId);
            } else {
                logger.warn("🔍 SearchService.resolveOne: Multiple matches found ({}), returning MANY", rows.size());
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
        
        // Remove control parameters from search criteria
        searchParams.remove("_summary");
        searchParams.remove("_elements");
        searchParams.remove("_count");
        searchParams.remove("_sort");
        searchParams.remove("_total");
        
        // Build search queries
        List<SearchQuery> ftsQueries = buildSearchQueries(resourceType, searchParams);
        // Handle count-only queries
        if ("accurate".equals(totalMode) && count == 0) {
            return handleCountOnlyQuery(ftsQueries, resourceType, bucketName);
        }
        
        // Execute full search
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
                    .setFullUrl(resourceType + "/" + filteredResource.getIdElement().getIdPart());
        }
        
        logger.info("🔍 SearchService.search: Returning {} results for {}", results.size(), resourceType);
        return bundle;
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Build search queries from criteria parameters
     */
    private List<SearchQuery> buildSearchQueries(String resourceType, Map<String, String> criteria) {
        List<SearchQuery> ftsQueries = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        
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
            switch (searchParam.getParamType()) {
                case TOKEN:
                    ftsQueries.add(TokenSearchHelperFTS.buildTokenFTSQuery(fhirContext, resourceType, paramName, value));
                    break;
                case STRING:
                    ftsQueries.add(StringSearchHelperFTS.buildStringFTSQuery(fhirContext, resourceType, paramName, value, searchParam, modifier));
                    break;
                case DATE:
                    ftsQueries.add(DateSearchHelperFTS.buildDateFTS(fhirContext, resourceType, paramName, value));
                    break;
                case REFERENCE:
                    String referenceClause = ReferenceSearchHelper.buildReferenceWhereCluse(fhirContext, resourceType, paramName, value, searchParam);
                    filters.add(referenceClause);
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
}
