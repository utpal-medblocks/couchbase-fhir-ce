package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;
import com.couchbase.client.java.search.queries.MatchQuery;
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
                return buildUSCoreDateQueries(paramName, values.get(0), expression);
            case TOKEN:
                return buildUSCoreTokenQueries(paramName, values.get(0), expression);
            case STRING:
                return buildUSCoreStringQueries(paramName, values.get(0), expression);
            case REFERENCE:
                return buildUSCoreReferenceQueries(paramName, values.get(0), expression);
            default:
                logger.warn("🔍 USCoreSearchHelper: Unsupported US Core parameter type: {} for {}", paramType, paramName);
                return new ArrayList<>();
        }
    }

    /**
     * Build date queries for US Core parameters (may return multiple queries for extensions)
     */
    private static List<SearchQuery> buildUSCoreDateQueries(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building DATE queries for {}", paramName);
        List<SearchQuery> queries = new ArrayList<>();

        // Parse date value and prefixes
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

        // Handle extension-based expressions
        if (expression.contains("extension.where")) {
            // Extract extension URL and value field
            String extensionUrl = extractExtensionUrl(expression);
            String valueField = extractExtensionValueField(expression);
            
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
            
        } else {
            // Handle simple field expressions
            String fieldPath = extractFieldPathFromExpression(expression);
            DateRangeQuery query = SearchQuery.dateRange().field(fieldPath);
            if (start != null) query = query.start(start, inclusiveStart);
            if (end != null) query = query.end(end, inclusiveEnd);
            queries.add(query);
        }

        return queries;
    }

    /**
     * Build token query for US Core parameters
     */
    private static SearchQuery buildUSCoreTokenQuery(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building TOKEN query for {}", paramName);
        
        String fieldPath = extractFieldPathFromExpression(expression);
        logger.info("🔍 USCoreSearchHelper: Extracted field path: {}", fieldPath);

        return SearchQuery.match(searchValue).field(fieldPath);
    }

    /**
     * Build string query for US Core parameters
     */
    private static SearchQuery buildUSCoreStringQuery(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building STRING query for {}", paramName);
        
        String fieldPath = extractFieldPathFromExpression(expression);
        logger.info("🔍 USCoreSearchHelper: Extracted field path: {}", fieldPath);

        return SearchQuery.match(searchValue).field(fieldPath);
    }

    /**
     * Build reference query for US Core parameters
     */
    private static SearchQuery buildUSCoreReferenceQuery(String paramName, String searchValue, String expression) {
        logger.info("🔍 USCoreSearchHelper: Building REFERENCE query for {}", paramName);
        
        String fieldPath = extractFieldPathFromExpression(expression);
        logger.info("🔍 USCoreSearchHelper: Extracted field path: {}", fieldPath);

        return SearchQuery.match(searchValue).field(fieldPath);
    }

    /**
     * Extract field path from FHIRPath expression
     * This is where the complex logic for handling extensions, where clauses, etc. goes
     */
    private static String extractFieldPathFromExpression(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            logger.warn("🔍 USCoreSearchHelper: Empty expression, using fallback");
            return "unknown";
        }

        logger.info("🔍 USCoreSearchHelper: Parsing expression: {}", expression);

        // Handle extension-based expressions
        if (expression.contains("extension.where")) {
            return handleExtensionExpression(expression);
        }

        // Handle simple field expressions like "Condition.assertedDate"
        if (expression.contains(".") && !expression.contains("(")) {
            String[] parts = expression.split("\\.");
            if (parts.length >= 2) {
                String fieldName = parts[1];
                logger.info("🔍 USCoreSearchHelper: Simple field extraction: {}", fieldName);
                return fieldName;
            }
        }

        // Fallback - log the expression and return as-is for now
        logger.warn("🔍 USCoreSearchHelper: Complex expression not yet handled: {}", expression);
        return "extension"; // Fallback for extension-based searches
    }

    /**
     * Handle extension-based FHIRPath expressions
     * e.g., "Condition.extension.where(url = 'http://...').valueDateTime"
     */
    private static String handleExtensionExpression(String expression) {
        logger.info("🔍 USCoreSearchHelper: Handling extension expression: {}", expression);

        // For now, we'll use a simple approach for extension searches
        // This assumes the extension value is indexed in a searchable way in Couchbase
        
        // Extract the final field (e.g., "valueDateTime" from the expression)
        String finalField = "extension";
        if (expression.contains(".value")) {
            int valueIndex = expression.lastIndexOf(".value");
            String valuePart = expression.substring(valueIndex + 1);
            if (valuePart.contains(" ") || valuePart.contains(")")) {
                valuePart = valuePart.split("[ )]")[0];
            }
            finalField = "extension." + valuePart;
        }

        logger.info("🔍 USCoreSearchHelper: Extension field path: {}", finalField);
        return finalField;
    }

    /**
     * Extract extension URL from FHIRPath expression
     * e.g., from "Condition.extension.where(url = 'http://...').valueDateTime"
     * extract "http://..."
     */
    private static String extractExtensionUrl(String expression) {
        if (expression.contains("where(url = '")) {
            int start = expression.indexOf("where(url = '") + "where(url = '".length();
            int end = expression.indexOf("')", start);
            if (end > start) {
                String url = expression.substring(start, end);
                logger.info("🔍 USCoreSearchHelper: Extracted extension URL: {}", url);
                return url;
            }
        }
        return null;
    }

    /**
     * Extract extension value field from FHIRPath expression
     * e.g., from "Condition.extension.where(url = 'http://...').valueDateTime"
     * extract "extension.valueDateTime"
     */
    private static String extractExtensionValueField(String expression) {
        if (expression.contains(".value")) {
            int valueIndex = expression.lastIndexOf(".value");
            String valuePart = expression.substring(valueIndex + 1);
            if (valuePart.contains(" ") || valuePart.contains(")")) {
                valuePart = valuePart.split("[ )]")[0];
            }
            String field = "extension." + valuePart;
            logger.info("🔍 USCoreSearchHelper: Extracted extension value field: {}", field);
            return field;
        }
        return "extension.value";
    }

    // Placeholder methods for other types - return single query for now
    private static List<SearchQuery> buildUSCoreTokenQueries(String paramName, String searchValue, String expression) {
        List<SearchQuery> queries = new ArrayList<>();
        String fieldPath = extractFieldPathFromExpression(expression);
        queries.add(SearchQuery.match(searchValue).field(fieldPath));
        return queries;
    }

    private static List<SearchQuery> buildUSCoreStringQueries(String paramName, String searchValue, String expression) {
        List<SearchQuery> queries = new ArrayList<>();
        String fieldPath = extractFieldPathFromExpression(expression);
        queries.add(SearchQuery.match(searchValue).field(fieldPath));
        return queries;
    }

    private static List<SearchQuery> buildUSCoreReferenceQueries(String paramName, String searchValue, String expression) {
        List<SearchQuery> queries = new ArrayList<>();
        String fieldPath = extractFieldPathFromExpression(expression);
        queries.add(SearchQuery.match(searchValue).field(fieldPath));
        return queries;
    }
}
