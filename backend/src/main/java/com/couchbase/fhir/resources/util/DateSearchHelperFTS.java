package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;

public class DateSearchHelperFTS {

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {

        String start = null;
        String end = null;
        boolean inclusiveStart = true;
        boolean inclusiveEnd = true;
        if (searchValue.startsWith("gt")) {
            start = searchValue.substring(2);
            inclusiveStart = false;
        } else if (searchValue.startsWith("ge")) {
            start = searchValue.substring(2);
        } else if (searchValue.startsWith("lt")) {
            end = searchValue.substring(2);
            inclusiveEnd = false;
        } else if (searchValue.startsWith("le")) {
            end = searchValue.substring(2);
        } else {
            start = searchValue;
            end = searchValue;
        }
        if (paramName.equalsIgnoreCase("death-date")) {
            paramName = "deceasedDateTime";
        }

        DateRangeQuery query = SearchQuery.dateRange().field(paramName);

        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

}
