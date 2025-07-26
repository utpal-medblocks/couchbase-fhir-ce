package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;

public class DateSearchHelper {
    public static String buildDateCondition(FhirContext fhirContext, String resourceType, String paramName, String searchValue){


        String operator = "=";
        String dateValue = searchValue;

        if (searchValue.startsWith("gt")) {
            operator = ">";
            dateValue = searchValue.substring(2);
        } else if (searchValue.startsWith("ge")) {
            operator = ">=";
            dateValue = searchValue.substring(2);
        } else if (searchValue.startsWith("lt")) {
            operator = "<";
            dateValue = searchValue.substring(2);
        } else if (searchValue.startsWith("le")) {
            operator = "<=";
            dateValue = searchValue.substring(2);
        }

        return String.format(" %s %s \"%s\" ", paramName, operator, dateValue);
    }

}
