package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DisjunctionQuery;
import org.hl7.fhir.r4.model.StringType;
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
        String fhirPath = rawPath.replaceFirst("^" + resourceType + "\\.", "");
        String[] pathParts = fhirPath.split("\\.");

        List<String> subFields;
        if (pathParts.length == 1) {
            subFields = getSubFields(fhirContext, resourceType, paramName);
        } else {
            subFields = getCompositeSubFields(fhirContext, resourceType, fhirPath);
        }

        if (subFields.isEmpty()) {
            logger.warn("🔍 StringSearchHelper: No sub-fields found for paramName={}, fhirPath={}", paramName, fhirPath);
            return null;
        }

        logger.info("🔍 StringSearchHelper: paramName={}, fhirPath={}, subFields={}", paramName, fhirPath, subFields);

        List<SearchQuery> fieldQueries = new ArrayList<>();
        boolean isExact = "exact".equalsIgnoreCase(modifier);

        for (String field : subFields) {
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

    private static List<String> getSubFields(FhirContext ctx, String resource, String fieldName) {
        List<String> fields = new ArrayList<>();
        BaseRuntimeElementCompositeDefinition<?> resourceDef =
                (BaseRuntimeElementCompositeDefinition<?>) ctx.getResourceDefinition(resource);
        BaseRuntimeChildDefinition fieldChild = resourceDef.getChildByName(fieldName);

        if (fieldChild == null) return fields;

        BaseRuntimeElementDefinition<?> fieldType = fieldChild.getChildByName(fieldName);
        if (fieldType instanceof BaseRuntimeElementCompositeDefinition<?>) {
            BaseRuntimeElementCompositeDefinition<?> compDef =
                    (BaseRuntimeElementCompositeDefinition<?>) fieldType;

            for (BaseRuntimeChildDefinition sub : compDef.getChildren()) {
                if (sub.getChildNameByDatatype(StringType.class) != null) {
                    String subName = sub.getElementName();
                    if (skipCommonIgnoredFields(subName)) continue;
                    fields.add(fieldName + "." + subName);
                }
            }
        }
        return fields;
    }

    private static List<String> getCompositeSubFields(FhirContext ctx, String resource, String fhirPath) {
        List<String> fields = new ArrayList<>();
        String[] parts = fhirPath.split("\\.");
        String parent = parts[0];
        String child = parts[1];
        fields.add(parent + "." + child);
        return fields;
    }

    private static boolean skipCommonIgnoredFields(String name) {
        return name.equals("id") || name.equals("extension") || name.equals("period") || name.equals("use");
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

