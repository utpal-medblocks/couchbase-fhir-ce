package com.couchbase.fhir.resources.util;


import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.CollectionRoutingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Component
public class Ftsn1qlQueryBuilder {

    private static final String DEFAULT_SCOPE = "Resources";
    private static final Logger logger = LoggerFactory.getLogger(Ftsn1qlQueryBuilder.class);
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    /**
     * Determine if resourceType filter is needed based on collection type
     * @param resourceType The FHIR resource type
     * @return true if resourceType filter should be added (for General collection), false for dedicated collections
     */
    private boolean shouldIncludeResourceTypeFilter(String resourceType) {
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        // Only add resourceType filter if going to General collection (mixed resource types)
        // Dedicated collections (Patient, Observation, etc.) don't need resourceType filter
        return "General".equals(targetCollection);
    }

    public String buildIdOnly(
            List<SearchQuery> mustQueries,
            String resourceType,
            int from,
            int size,
            List<SortField> sortFields
    ) {

        String bucketName = TenantContextHolder.getTenantId();

        JsonObject queryBody;
        
        // Only add resource type filter for General collection (mixed resource types)
        // Dedicated collections don't need resourceType filter since they contain only one resource type
        boolean needsResourceTypeFilter = shouldIncludeResourceTypeFilter(resourceType);
        JsonObject resourceTypeFilter = null;
        
        if (needsResourceTypeFilter) {
            resourceTypeFilter = JsonObject.create()
                .put("field", "resourceType")
                .put("match", resourceType);
        }
        
        // Build the main query structure
        if (mustQueries.isEmpty()) {
            if (needsResourceTypeFilter) {
                // No search criteria - just filter by resource type (General collection only)
                queryBody = resourceTypeFilter;
            } else {
                // No search criteria and no resourceType filter needed (dedicated collection)
                // Use match_all query
                queryBody = JsonObject.create().put("match_all", JsonObject.create());
            }
        } else if (mustQueries.size() == 1) {
            if (needsResourceTypeFilter) {
                // Single query - combine with resource type filter using conjuncts
                queryBody = JsonObject.create().put(
                    "conjuncts",
                    List.of(resourceTypeFilter, mustQueries.get(0).export())
                );
            } else {
                // Single query - no resourceType filter needed
                queryBody = mustQueries.get(0).export();
            }
        } else {
            // Multiple queries
            List<JsonObject> allQueries = new ArrayList<>();
            if (needsResourceTypeFilter) {
                allQueries.add(resourceTypeFilter);
            }
            allQueries.addAll(mustQueries.stream().map(SearchQuery::export).collect(Collectors.toList()));
            queryBody = JsonObject.create().put("conjuncts", allQueries);
        }

        JsonObject ftsDsl = JsonObject.create()
                .put("size", size)
                .put("from", from)
                .put("query", queryBody);
        
        // Add sort if specified
        if (sortFields != null && !sortFields.isEmpty()) {
            JsonArray sortArray = JsonArray.create();
            
            for (SortField sortField : sortFields) {
                JsonObject sortObject = JsonObject.create()
                        .put("by", "field")
                        .put("field", sortField.field)  // Use field exactly as provided
                        .put("desc", sortField.descending);
                sortArray.add(sortObject);
            }
            
            ftsDsl.put("sort", sortArray);
        }

        // Get the correct target collection and FTS index for this resource type
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
        
        if (ftsIndex == null) {
            throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
        }

        // Build the WHERE clause
        StringBuilder whereClause = new StringBuilder();
        whereClause.append("SEARCH(resource, ").append(ftsDsl.toString()).append(", {\"index\":\"").append(ftsIndex).append("\"})");
        
        String n1ql = String.format(
                "SELECT META(resource).id as id " +
                        "FROM `%s`.`%s`.`%s` resource " +
                        "WHERE %s",
                bucketName, DEFAULT_SCOPE, targetCollection,
                whereClause.toString()
        );
        logger.info("🔍 Query ID: {}", n1ql);
        return n1ql;
    }
    
    /**
     * Build query with both FTS queries and N1QL filters
     */
    public String build(
            List<SearchQuery> mustQueries,
            List<String> n1qlFilters,
            String resourceType,
            int from,
            int size,
            List<SortField> sortFields
    ) {
        String bucketName = TenantContextHolder.getTenantId();

        JsonObject queryBody;
        
        // Only add resource type filter for General collection (mixed resource types)
        // Dedicated collections don't need resourceType filter since they contain only one resource type
        boolean needsResourceTypeFilter = shouldIncludeResourceTypeFilter(resourceType);
        JsonObject resourceTypeFilter = null;
        
        if (needsResourceTypeFilter) {
            resourceTypeFilter = JsonObject.create()
                .put("field", "resourceType")
                .put("match", resourceType);
        }
        
        // Build the main query structure
        if (mustQueries.isEmpty()) {
            if (needsResourceTypeFilter) {
                // No search criteria - just filter by resource type (General collection only)
                queryBody = resourceTypeFilter;
            } else {
                // No search criteria and no resourceType filter needed (dedicated collection)
                // Use match_all query
                queryBody = JsonObject.create().put("match_all", JsonObject.create());
            }
        } else if (mustQueries.size() == 1) {
            if (needsResourceTypeFilter) {
                // Single query - combine with resource type filter using conjuncts
                queryBody = JsonObject.create().put(
                    "conjuncts",
                    List.of(resourceTypeFilter, mustQueries.get(0).export())
                );
            } else {
                // Single query - no resourceType filter needed
                queryBody = mustQueries.get(0).export();
            }
        } else {
            // Multiple queries
            List<JsonObject> allQueries = new ArrayList<>();
            if (needsResourceTypeFilter) {
                allQueries.add(resourceTypeFilter);
            }
            allQueries.addAll(mustQueries.stream().map(SearchQuery::export).collect(Collectors.toList()));
            queryBody = JsonObject.create().put("conjuncts", allQueries);
        }

        JsonObject ftsDsl = JsonObject.create()
                .put("size", size)
                .put("from", from)
                .put("query", queryBody);
        
        // Add sort if specified
        if (sortFields != null && !sortFields.isEmpty()) {
            JsonArray sortArray = JsonArray.create();
            
            for (SortField sortField : sortFields) {
                JsonObject sortObject = JsonObject.create()
                        .put("by", "field")
                        .put("field", sortField.field)  // Use field exactly as provided
                        .put("desc", sortField.descending);
                sortArray.add(sortObject);
            }
            
            ftsDsl.put("sort", sortArray);
        }

        // Get the correct target collection and FTS index for this resource type
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
        
        if (ftsIndex == null) {
            throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
        }

        // Build the WHERE clause
        StringBuilder whereClause = new StringBuilder();
        whereClause.append("SEARCH(resource, ").append(ftsDsl.toString()).append(", {\"index\":\"").append(ftsIndex).append("\"})");
        
        // Add N1QL filters if any
        if (n1qlFilters != null && !n1qlFilters.isEmpty()) {
            for (String filter : n1qlFilters) {
                whereClause.append(" AND ").append(filter);
            }
        }
        
        String n1ql = String.format(
                "SELECT resource.* " +
                        "FROM `%s`.`%s`.`%s` resource " +
                        "WHERE %s",
                bucketName, DEFAULT_SCOPE, targetCollection,
                whereClause.toString()
        );
        logger.info("🔍 Query Full: {}", n1ql);
        return n1ql;
    }
    
    /**
     * Backward compatibility method without sort parameters
     */
    public String build(
            List<SearchQuery> mustQueries,
            String resourceType,
            int from,
            int size
    ) {
        return buildIdOnly(mustQueries, resourceType, from, size, new ArrayList<>());
    }
    
    /**
     * Build COUNT query for _total=accurate operations
     */
    public String buildCountQuery(
            List<SearchQuery> mustQueries,
            String resourceType
    ) {
        String bucketName = TenantContextHolder.getTenantId();

        JsonObject queryBody;
        
        // Only add resource type filter for General collection (mixed resource types)
        // Dedicated collections don't need resourceType filter since they contain only one resource type
        boolean needsResourceTypeFilter = shouldIncludeResourceTypeFilter(resourceType);
        JsonObject resourceTypeFilter = null;
        
        if (needsResourceTypeFilter) {
            resourceTypeFilter = JsonObject.create()
                .put("field", "resourceType")
                .put("match", resourceType);
        }
        
        // Use same logic as main build method
        if (mustQueries.isEmpty()) {
            if (needsResourceTypeFilter) {
                // No search criteria - just filter by resource type (General collection only)
                queryBody = resourceTypeFilter;
            } else {
                // No search criteria and no resourceType filter needed (dedicated collection)
                // Use match_all query
                queryBody = JsonObject.create().put("match_all", JsonObject.create());
            }
        } else if (mustQueries.size() == 1) {
            if (needsResourceTypeFilter) {
                // Single query - combine with resource type filter using conjuncts
                queryBody = JsonObject.create().put(
                    "conjuncts",
                    List.of(resourceTypeFilter, mustQueries.get(0).export())
                );
            } else {
                // Single query - no resourceType filter needed
                queryBody = mustQueries.get(0).export();
            }
        } else {
            // Multiple queries
            List<JsonObject> allQueries = new ArrayList<>();
            if (needsResourceTypeFilter) {
                allQueries.add(resourceTypeFilter);
            }
            allQueries.addAll(mustQueries.stream().map(SearchQuery::export).collect(Collectors.toList()));
            queryBody = JsonObject.create().put("conjuncts", allQueries);
        }

        JsonObject ftsDsl = JsonObject.create()
                .put("size", 0)  // Don't return documents, just count
                .put("from", 0)
                .put("query", queryBody);

        // Get the correct target collection and FTS index for this resource type
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
        
        if (ftsIndex == null) {
            throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
        }

        String n1ql = String.format(
                "SELECT COUNT(*) as total " +
                        "FROM `%s`.`%s`.`%s` resource " +
                        "WHERE SEARCH(resource, %s, {\"index\":\"%s\"})",
                bucketName, DEFAULT_SCOPE, targetCollection,
                ftsDsl.toString(),
                ftsIndex
        );
        logger.info("🔍 Query Count: {}", n1ql);
        return n1ql;
    }
    
    /**
     * Simple class to hold sort field information
     */
    public static class SortField {
        public final String field;
        public final boolean descending;
        
        public SortField(String field, boolean descending) {
            this.field = field;
            this.descending = descending;
        }
    }
    
    /**
     * Check if a field is a datetime field (for proper sorting)
     */
    private boolean isDateTimeField(String fieldName) {
        // Common FHIR datetime fields
        return fieldName.contains("date") || fieldName.contains("Date") || 
               fieldName.contains("time") || fieldName.contains("Time") ||
               fieldName.equals("_lastUpdated") || fieldName.equals("meta.lastUpdated") ||
               fieldName.equals("birthDate") || fieldName.equals("effectiveDateTime") ||
               fieldName.contains("period.start") || fieldName.contains("period.end");
    }

}
