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

@Component
public class Ftsn1qlQueryBuilder {

    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;

    public String buildIdOnly(
            List<SearchQuery> mustQueries,
            String resourceType,
            int from,
            int size,
            List<SortField> sortFields
    ) {

        String bucketName = TenantContextHolder.getTenantId();

        JsonObject queryBody;
        
        // Always add resource type filter since multiple resource types are in the same collection
        JsonObject resourceTypeFilter = JsonObject.create()
            .put("field", "resourceType")
            .put("match", resourceType);
        
        // Build the main query structure
        if (mustQueries.isEmpty()) {
            // No search criteria - just filter by resource type
            queryBody = resourceTypeFilter;
        } else if (mustQueries.size() == 1) {
            // Single query - combine with resource type filter using conjuncts
            queryBody = JsonObject.create().put(
                "conjuncts",
                List.of(resourceTypeFilter, mustQueries.get(0).export())
            );
        } else {
            // Multiple queries - combine resource type filter with all other queries using conjuncts
            List<JsonObject> allQueries = new ArrayList<>();
            allQueries.add(resourceTypeFilter);
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

        System.out.println("query (ID-only): "+n1ql);
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
        
        // Always add resource type filter since multiple resource types are in the same collection
        JsonObject resourceTypeFilter = JsonObject.create()
            .put("field", "resourceType")
            .put("match", resourceType);
        
        // Build the main query structure
        if (mustQueries.isEmpty()) {
            // No search criteria - just filter by resource type
            queryBody = resourceTypeFilter;
        } else if (mustQueries.size() == 1) {
            // Single query - combine with resource type filter using conjuncts
            queryBody = JsonObject.create().put(
                "conjuncts",
                List.of(resourceTypeFilter, mustQueries.get(0).export())
            );
        } else {
            // Multiple queries - combine resource type filter with all other queries using conjuncts
            List<JsonObject> allQueries = new ArrayList<>();
            allQueries.add(resourceTypeFilter);
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

        System.out.println("query (full resource with filters): "+n1ql);
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
        
        // Always add resource type filter since multiple resource types are in the same collection
        JsonObject resourceTypeFilter = JsonObject.create()
            .put("field", "resourceType")
            .put("match", resourceType);
        
        // Use same logic as main build method
        if (mustQueries.isEmpty()) {
            // No search criteria - just filter by resource type
            queryBody = resourceTypeFilter;
        } else if (mustQueries.size() == 1) {
            // Single query - combine with resource type filter using conjuncts
            queryBody = JsonObject.create().put(
                "conjuncts",
                List.of(resourceTypeFilter, mustQueries.get(0).export())
            );
        } else {
            // Multiple queries - combine resource type filter with all other queries using conjuncts
            List<JsonObject> allQueries = new ArrayList<>();
            allQueries.add(resourceTypeFilter);
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

        System.out.println("count query " + n1ql);
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
