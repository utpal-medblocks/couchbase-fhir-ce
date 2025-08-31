package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DateSearchHelperFTS {

    private static final Logger logger = LoggerFactory.getLogger(DateSearchHelperFTS.class);

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
        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 DateSearchHelperFTS: paramName={}, HAPI path={}", paramName, path);
                
                // Extract the field name from the path (remove resourceType prefix)
                if (path != null && path.startsWith(resourceType + ".")) {
                    actualFieldName = path.substring(resourceType.length() + 1);
                    logger.info("🔍 DateSearchHelperFTS: Using field name: {}", actualFieldName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelperFTS: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Handle special cases
        if (paramName.equalsIgnoreCase("death-date")) {
            actualFieldName = "deceasedDateTime";
        }

        DateRangeQuery query = SearchQuery.dateRange().field(actualFieldName);

        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

}
