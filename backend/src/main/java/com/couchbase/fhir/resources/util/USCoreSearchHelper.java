package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;

import java.util.ArrayList;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.Enumerations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Helper class for building FTS queries from US Core search parameters
 * Handles complex FHIRPath expressions and extension-based searches
 */
public class USCoreSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(USCoreSearchHelper.class);

    /**
     * Build FTS queries for US Core search parameter
     * Returns multiple queries for extension-based searches (URL + value)
     */
    public static List<SearchQuery> buildUSCoreFTSQueries(FhirContext fhirContext, String resourceType, 
                                                          String paramName, List<String> values, 
                                                          SearchParameter usCoreParam) {
        if (values == null || values.isEmpty() || usCoreParam == null) {
            return new ArrayList<>();
        }

        logger.info("🔍 USCoreSearchHelper: Building query for {} parameter: {}", resourceType, paramName);
        logger.info("🔍 USCoreSearchHelper: Expression: {}", usCoreParam.getExpression());
        logger.info("🔍 USCoreSearchHelper: Type: {}", usCoreParam.getType());

        // Handle based on parameter type
        Enumerations.SearchParamType paramType = usCoreParam.getType();
        String expression = usCoreParam.getExpression();

        switch (paramType) {
            case DATE:
                return buildUSCoreDateQueries(fhirContext, resourceType, paramName, values.get(0), expression);
            case TOKEN:
                SearchQuery tokenQuery = buildUSCoreTokenQuery(fhirContext, resourceType, paramName, values.get(0), expression);
                return List.of(tokenQuery);
            case STRING:
                SearchQuery stringQuery = buildUSCoreStringQuery(paramName, values.get(0), expression);
                return List.of(stringQuery);
            case REFERENCE:
                SearchQuery referenceQuery = buildUSCoreReferenceQuery(paramName, values.get(0), expression);
                return List.of(referenceQuery);
            default:
                logger.warn("🔍 USCoreSearchHelper: Unsupported US Core parameter type: {} for {}", paramType, paramName);
                return new ArrayList<>();
        }
    }

    /**
     * Build date queries for US Core parameters - leverages sophisticated DateSearchHelper
     */
    private static List<SearchQuery> buildUSCoreDateQueries(FhirContext fhirContext, String resourceType, 
                                                           String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building DATE queries for {}", paramName);
        
        // Use FHIRPathParser to parse the expression
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(expression);
        
        if (parsed.isExtension()) {
            // Handle extension-based expressions - these need special US Core handling
            return buildExtensionDateQueries(paramName, searchValue, parsed);
        } else {
            // Handle regular field expressions - delegate to sophisticated DateSearchHelper
            logger.info("🔍 USCoreSearchHelper: Delegating to DateSearchHelper for regular field: {}", expression);
            
            // Use DateSearchHelper's sophisticated logic for choice types, Period handling, etc.
            SearchQuery query = DateSearchHelper.buildDateFTS(fhirContext, resourceType, paramName, searchValue);
            return query != null ? List.of(query) : new ArrayList<>();
        }
    }

    /**
     * Build extension-based date queries (US Core specific)
     */
    private static List<SearchQuery> buildExtensionDateQueries(String paramName, String searchValue, 
                                                              FHIRPathParser.ParsedExpression parsed) {
        logger.info("🔍 USCoreSearchHelper: Building extension DATE queries for {}", paramName);
        List<SearchQuery> queries = new ArrayList<>();

        // Parse date value and prefixes (reuse DateSearchHelper logic would be ideal, but extensions are special)
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

        String extensionUrl = parsed.getExtensionUrl();
        String valueField = parsed.getExtensionValueField();
        
        if (extensionUrl != null) {
            // Add query for extension URL
            SearchQuery urlQuery = SearchQuery.match(extensionUrl).field("extension.url");
            queries.add(urlQuery);
            logger.info("🔍 USCoreSearchHelper: Added extension URL query: {}", urlQuery.export());
        }
        
        // Add query for extension value
        DateRangeQuery valueQuery = SearchQuery.dateRange().field(valueField);
        if (start != null) valueQuery = valueQuery.start(start, inclusiveStart);
        if (end != null) valueQuery = valueQuery.end(end, inclusiveEnd);
        queries.add(valueQuery);
        logger.info("🔍 USCoreSearchHelper: Added extension value query: {}", valueQuery.export());
        
        return queries;
    }

    /**
     * Build token query for US Core parameters
     */
    private static SearchQuery buildUSCoreTokenQuery(FhirContext fhirContext, String resourceType, String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building TOKEN query for {}", paramName);
        
        // Delegate to enhanced TokenSearchHelper for comprehensive TOKEN type support
        return TokenSearchHelper.buildTokenFTSQueryFromExpression(fhirContext, resourceType, expression, searchValue);
    }

    /**
     * Build string query for US Core parameters
     */
    private static SearchQuery buildUSCoreStringQuery(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building STRING query for {}", paramName);
        
        // Use FHIRPathParser to parse the expression
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(expression);
        String fieldPath = parsed.getPrimaryFieldPath();
        
        if (fieldPath == null) {
            logger.warn("🔍 USCoreSearchHelper: Could not extract field path from: {}", expression);
            fieldPath = "unknown";
        }
        
        logger.info("🔍 USCoreSearchHelper: Extracted field path: {}", fieldPath);
        return SearchQuery.match(searchValue).field(fieldPath);
    }

    /**
     * Build reference query for US Core parameters
     */
    private static SearchQuery buildUSCoreReferenceQuery(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building REFERENCE query for {}", paramName);
        
        // Use FHIRPathParser to parse the expression
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(expression);
        String fieldPath = parsed.getPrimaryFieldPath();
        
        if (fieldPath == null) {
            logger.warn("🔍 USCoreSearchHelper: Could not extract field path from: {}", expression);
            fieldPath = "unknown";
        }
        
        logger.info("🔍 USCoreSearchHelper: Extracted field path: {}", fieldPath);
        return SearchQuery.match(searchValue).field(fieldPath);
    }
}