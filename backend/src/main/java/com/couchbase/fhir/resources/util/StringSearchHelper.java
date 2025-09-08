package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DisjunctionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class StringSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(StringSearchHelper.class);

    public static SearchQuery buildStringFTSQuery(FhirContext fhirContext,
                                                 String resourceType,
                                                 String paramName,
                                                 String searchValue,
                                                 RuntimeSearchParam searchParam, String modifier) {

        String rawPath = searchParam.getPath();
        logger.info("🔍 StringSearchHelper: paramName={}, rawPath={}", paramName, rawPath);
        
        // Use FHIRPathParser to properly handle union expressions
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(rawPath);
        List<String> fieldPaths = new ArrayList<>();
        
        if (parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
            // Union expression like "name | Organization.alias"
            fieldPaths.addAll(parsed.getFieldPaths());
            logger.info("🔍 StringSearchHelper: Parsed union expression into {} fields: {}", fieldPaths.size(), fieldPaths);
        } else {
            // Single field
            String fieldPath = parsed.getPrimaryFieldPath();
            if (fieldPath == null) {
                // Fallback to legacy parsing
                String fhirPath = rawPath.replaceFirst("^" + resourceType + "\\.", "");
                fieldPath = fhirPath;
            }
            fieldPaths.add(fieldPath);
            logger.info("🔍 StringSearchHelper: Parsed single field: {}", fieldPath);
        }

        if (fieldPaths.isEmpty()) {
            logger.warn("🔍 StringSearchHelper: No field paths found for paramName={}, rawPath={}", paramName, rawPath);
            return null;
        }

        logger.info("🔍 StringSearchHelper: paramName={}, fieldPaths={}", paramName, fieldPaths);

        List<SearchQuery> fieldQueries = new ArrayList<>();
        boolean isExact = "exact".equalsIgnoreCase(modifier);

        for (String field : fieldPaths) {
            if (isExact) {
                String exactField = field + "Exact";
                fieldQueries.add(SearchQuery.match(searchValue).field(exactField));
            } else {
                fieldQueries.add(SearchQuery.prefix(searchValue.toLowerCase()).field(field));
            }
        }

        DisjunctionQuery disjunction = SearchQuery.disjuncts(fieldQueries.toArray(new SearchQuery[0]));
        
        // Return the disjunction directly - no need for nested conjunctions
        return disjunction;
    }


    public static SearchQuery buildStringFTSQueryWithMultipleValues(FhirContext fhirContext, String resourceType, String paramName, List<String> searchValues, RuntimeSearchParam searchParam, String modifier) {
        if (searchValues == null || searchValues.isEmpty()) {
            return null;
        }

        if (searchValues.size() == 1) {
            return buildStringFTSQuery(fhirContext, resourceType, paramName, searchValues.get(0), searchParam, modifier);
        }

        // Multiple values: create OR query
        List<SearchQuery> queries = new ArrayList<>();
        for (String value : searchValues) {
            SearchQuery query = buildStringFTSQuery(fhirContext, resourceType, paramName, value, searchParam, modifier);
            if (query != null) {
                queries.add(query);
            }
        }

        if (queries.isEmpty()) {
            return null;
        }

        if (queries.size() == 1) {
            return queries.get(0);
        }

        return SearchQuery.disjuncts(queries.toArray(new SearchQuery[0]));
    }
}

