package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import com.couchbase.fhir.resources.queries.Queries;

import java.util.ArrayList;
import java.util.List;

public class QueryBuilder {

    public String buildQuery(List<String> filters , List<String> revIncludes , String resourceType){

        String finalQuery = "";
        List<String> revQuery = new ArrayList<String>();

        //Building first query
        String whereClause = filters.isEmpty() ? "" : "WHERE " + String.join(" AND ", filters);
        String topLevelQuery =  String.format(Queries.SEARCH_QUERY_TOP_LEVEL,
                "fhir", "Resources", resourceType, whereClause);

        if(!revIncludes.isEmpty()){
            for(String revInclude : revIncludes){
                String[] revParts = revInclude.split(":");
                String sourceResource = revParts[0];
                String referenceField = revParts[1];
                String queryPart = String.format( Queries.SEARCH_QUERY_REV_INCLUDE ,sourceResource ,sourceResource, referenceField ,resourceType  , resourceType, String.join(" AND ", filters) );
                revQuery.add(queryPart);
            }
        }

        finalQuery = topLevelQuery + (revQuery.isEmpty() ? "" : " UNION ALL "+String.join(" UNION ALL ", revQuery));
        System.out.println("finalQuery = " + finalQuery);
        return finalQuery;
    }


    public static String getActualFieldName(FhirContext ctx, String resourceName, String searchParam) {
        RuntimeResourceDefinition def = ctx.getResourceDefinition(resourceName);
        for (BaseRuntimeChildDefinition child : def.getChildren()) {
            String elementName = child.getElementName();

            if (elementName.equalsIgnoreCase(searchParam)) {
                return elementName;
            }
        }
        return searchParam;
    }
}
