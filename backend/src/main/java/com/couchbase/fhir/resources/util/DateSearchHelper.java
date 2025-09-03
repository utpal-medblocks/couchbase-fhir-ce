package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DateSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(DateSearchHelper.class);

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        if (searchValue == null || searchValue.isEmpty()) {
            return null;
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
            // For exact dates, set both start and end
            start = searchValue;
            end = searchValue;
        }

        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 DateSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Extract the field name from the path (remove resourceType prefix)
                if (path != null && path.startsWith(resourceType + ".")) {
                    actualFieldName = path.substring(resourceType.length() + 1);
                    logger.info("🔍 DateSearchHelper: Using field name: {}", actualFieldName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Handle special cases (like parent project)
        if (paramName.equalsIgnoreCase("death-date")) {
            actualFieldName = "deceasedDateTime";
        }

        logger.info("🔍 DateSearchHelper: paramName={}, fhirPath={}, start={}, end={}", 
                   paramName, actualFieldName, start, end);

        // Check if this is a Period field and adjust field path accordingly
        boolean isPeriodField = isPeriodType(fhirContext, resourceType, actualFieldName);
        String dateField = actualFieldName;

        if (isPeriodField) {
            if (start != null) {
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", dateField);
            } else if (end != null) {
                dateField = actualFieldName + ".end";
                logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", dateField);
            } else {
                // For exact dates on Period fields, use start
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start for exact date: {}", dateField);
            }
        } else {
            logger.info("🔍 DateSearchHelper: Non-Period field, using as-is: {}", dateField);
        }

        DateRangeQuery query = SearchQuery.dateRange().field(dateField);
        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, List<String> searchValues) {
        if (searchValues == null || searchValues.isEmpty()) {
            return null;
        }

        if (searchValues.size() == 1) {
            return buildDateFTS(fhirContext, resourceType, paramName, searchValues.get(0));
        }

        // Handle multiple date values (e.g., birthdate=ge1987-01-01&birthdate=le1987-12-31)
        String start = null;
        String end = null;
        boolean inclusiveStart = true;
        boolean inclusiveEnd = true;

        for (String searchValue : searchValues) {
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
                // For exact dates, set both start and end
                start = searchValue;
                end = searchValue;
            }
        }

        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 DateSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Extract the field name from the path (remove resourceType prefix)
                if (path != null && path.startsWith(resourceType + ".")) {
                    actualFieldName = path.substring(resourceType.length() + 1);
                    logger.info("🔍 DateSearchHelper: Using field name: {}", actualFieldName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Handle special cases (like parent project)
        if (paramName.equalsIgnoreCase("death-date")) {
            actualFieldName = "deceasedDateTime";
        }

        logger.info("🔍 DateSearchHelper: paramName={}, fhirPath={}, start={}, end={}", 
                   paramName, actualFieldName, start, end);

        // Check if this is a Period field and adjust field path accordingly
        boolean isPeriodField = isPeriodType(fhirContext, resourceType, actualFieldName);
        String dateField = actualFieldName;

        if (isPeriodField) {
            if (start != null) {
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", dateField);
            } else if (end != null) {
                dateField = actualFieldName + ".end";
                logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", dateField);
            } else {
                // For exact dates on Period fields, use start
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start for exact date: {}", dateField);
            }
        } else {
            logger.info("🔍 DateSearchHelper: Non-Period field, using as-is: {}", dateField);
        }

        DateRangeQuery query = SearchQuery.dateRange().field(dateField);

        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

    /**
     * Check if a field is a Period type using HAPI reflection
     */
    private static boolean isPeriodType(FhirContext fhirContext, String resourceType, String fieldName) {
        try {
            ca.uhn.fhir.context.RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            ca.uhn.fhir.context.BaseRuntimeChildDefinition fieldChild = resourceDef.getChildByName(fieldName);
            
            if (fieldChild != null) {
                ca.uhn.fhir.context.BaseRuntimeElementDefinition<?> fieldType = fieldChild.getChildByName(fieldName);
                if (fieldType != null) {
                    String className = fieldType.getImplementingClass().getSimpleName();
                    boolean isPeriod = "Period".equalsIgnoreCase(className);
                    logger.debug("🔍 DateSearchHelper: Field {} has type: {} (isPeriod: {})", fieldName, className, isPeriod);
                    return isPeriod;
                }
            }
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Failed to check if field {} is a Period type: {}", fieldName, e.getMessage());
        }
        return false;
    }
}
