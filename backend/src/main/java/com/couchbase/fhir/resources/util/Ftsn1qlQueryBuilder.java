package com.couchbase.fhir.resources.util;


import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Ftsn1qlQueryBuilder {

    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    public String build(
            List<SearchQuery> mustQueries,
            List<SearchQuery> mustNotQueries,
            String resourceType,
            int from,
            int size,
            List<SortField> sortFields
    ) {

        String bucketName = TenantContextHolder.getTenantId();

        JsonObject mustPart = JsonObject.create().put(
                "conjuncts",
                mustQueries.stream().map(SearchQuery::export).collect(Collectors.toList())
        );

        JsonObject queryBody = JsonObject.create()
                .put("must", mustPart);

        // Add must_not clause for deleted resources
        if (mustNotQueries != null && !mustNotQueries.isEmpty()) {
            JsonObject mustNotPart = JsonObject.create().put(
                    "disjuncts",
                    mustNotQueries.stream().map(SearchQuery::export).collect(Collectors.toList())
            );
            queryBody.put("must_not", mustNotPart);
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

        String indexName = bucketName+"."+DEFAULT_SCOPE+".fts"+resourceType;

        String n1ql = String.format(
                "SELECT resource.* " +
                        "FROM `%s`.`%s`.`%s` resource " +
                        "WHERE SEARCH(resource, %s, {\"index\":\"%s\"})",
                bucketName, DEFAULT_SCOPE, resourceType,
                ftsDsl.toString(),
                indexName
        );

        System.out.println("query "+n1ql);
        return n1ql;
    }
    
    /**
     * Backward compatibility method without sort parameters
     */
    public String build(
            List<SearchQuery> mustQueries,
            List<SearchQuery> mustNotQueries,
            String resourceType,
            int from,
            int size
    ) {
        return build(mustQueries, mustNotQueries, resourceType, from, size, new ArrayList<>());
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
