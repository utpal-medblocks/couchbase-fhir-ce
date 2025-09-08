package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;

public class DateSearchHelperFTS {

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, String searchValue , ValidationSupportChain validationSupportChain) {

        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        //    RuntimeSearchParam searchParam = def.getSearchParam(paramName);
        SearchHelper searchHelper = new SearchHelper();
        RuntimeSearchParam searchParam = searchHelper.getSearchParam(def , validationSupportChain , paramName);

        String fhirPath = searchParam.getPath();

        // TODO : USE this for things like context.period
        String subPath;
        if (fhirPath.contains("Resource")) {
            subPath = fhirPath.substring(9);
        } else {
            subPath = fhirPath.substring(resourceType.length() + 1);
        }



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

        // TODO : remove hardcoding and move to actualfiledname method
        if (paramName.equalsIgnoreCase("death-date")) {
            paramName = "deceasedDateTime";
        }
        if(paramName.equalsIgnoreCase("onset-date")){
            paramName="onsetDateTime";
        }
        if(paramName.equalsIgnoreCase("abatement-date")){
            paramName="abatementDateTime";
        }
        if(paramName.equalsIgnoreCase("asserted-date")){
            paramName="assertedDateTime";
        }
        if(paramName.equalsIgnoreCase("recorded-date")){
            paramName="recordedDate";
        }
        //TODO : use xapath and move to actualfiledname method
        if(paramName.equalsIgnoreCase("date")){
            paramName="effectiveDateTime";
        }

        DateRangeQuery query = SearchQuery.dateRange().field(paramName);

        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

}
