package com.couchbase.fhir.resources.util;


import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;

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
            int size
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

}
