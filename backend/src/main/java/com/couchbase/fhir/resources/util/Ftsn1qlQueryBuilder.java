package com.couchbase.fhir.resources.util;


import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.queries.Queries;

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
            int size
    ) {

        String bucketName = TenantContextHolder.getTenantId();

        JsonObject mustPart = JsonObject.create().put(
                "conjuncts",
                mustQueries.stream().map(SearchQuery::export).collect(Collectors.toList())
        );

  /*      JsonObject mustNotPart = JsonObject.create().put(
                "disjuncts",
                mustNotQueries.stream().map(SearchQuery::export).collect(Collectors.toList())
        ); */

        JsonObject queryBody = JsonObject.create()
                .put("must", mustPart);

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


    public List<String> buildRevIncludes(
            List<String> revIncludes,
            List<SearchQuery> mustQueries,
            List<SearchQuery> mustNotQueries,
            String resourceType,
            int from,
            int size
    ) {
        List<String> revQuery = new ArrayList<>();
        if(!revIncludes.isEmpty()){
            for(String revInclude : revIncludes){
                String[] revParts = revInclude.split(":");
                String sourceResource = revParts[0];
                String referenceField = revParts[1];


             //   String queryPart = String.format( Queries.SEARCH_QUERY_REV_INCLUDE ,sourceResource ,sourceResource, referenceField ,resourceType  , resourceType, String.join(" AND ", filters) );
              //  revQuery.add(queryPart);
            }
        }
        return null;
    }
}
