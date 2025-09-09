package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DisjunctionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
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
            for (String path : parsed.getFieldPaths()) {
                List<String> expandedPaths = expandStringField(fhirContext, resourceType, path);
                fieldPaths.addAll(expandedPaths);
            }
            logger.info("🔍 StringSearchHelper: Parsed union expression into {} fields: {}", fieldPaths.size(), fieldPaths);
        } else {
            // Single field
            String fieldPath = parsed.getPrimaryFieldPath();
            if (fieldPath == null) {
                // Fallback to legacy parsing
                String fhirPath = rawPath.replaceFirst("^" + resourceType + "\\.", "");
                fieldPath = fhirPath;
            }
            
            // Expand the field to handle complex types like name -> name.family, name.given, etc.
            List<String> expandedPaths = expandStringField(fhirContext, resourceType, fieldPath);
            fieldPaths.addAll(expandedPaths);
            logger.info("🔍 StringSearchHelper: Parsed single field '{}' expanded to: {}", fieldPath, expandedPaths);
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
    
    /**
     * Expand string fields to handle complex types like HumanName, Address, etc.
     * This method identifies fields that have nested string components and expands them accordingly.
     */
    public static List<String> expandStringField(FhirContext fhirContext, String resourceType, String fieldPath) {
        logger.info("🔍 StringSearchHelper: Expanding field: {} for resource: {}", fieldPath, resourceType);
        
        // Handle known complex string field patterns
        if ("name".equals(fieldPath)) {
            // HumanName has family, given, prefix, suffix components
            List<String> expandedFields = Arrays.asList(
                "name.family", 
                "name.given", 
                "name.prefix", 
                "name.suffix"
            );
            logger.info("🔍 StringSearchHelper: Expanded 'name' to: {}", expandedFields);
            return expandedFields;
        }
        
        if ("address".equals(fieldPath)) {
            // Address has line, city, district, state, postalCode, country components
            List<String> expandedFields = Arrays.asList(
                "address.line",
                "address.city", 
                "address.district", 
                "address.state", 
                "address.postalCode", 
                "address.country"
            );
            logger.info("🔍 StringSearchHelper: Expanded 'address' to: {}", expandedFields);
            return expandedFields;
        }
        
        if ("telecom".equals(fieldPath)) {
            // ContactPoint has value component for string searches
            List<String> expandedFields = Arrays.asList("telecom.value");
            logger.info("🔍 StringSearchHelper: Expanded 'telecom' to: {}", expandedFields);
            return expandedFields;
        }
        
        // Handle nested complex fields like "contact.name" -> "contact.name.family", "contact.name.given", etc.
        if (fieldPath.endsWith(".name")) {
            List<String> expandedFields = Arrays.asList(
                fieldPath + ".family", 
                fieldPath + ".given", 
                fieldPath + ".prefix", 
                fieldPath + ".suffix"
            );
            logger.info("🔍 StringSearchHelper: Expanded nested name field '{}' to: {}", fieldPath, expandedFields);
            return expandedFields;
        }
        
        if (fieldPath.endsWith(".address")) {
            List<String> expandedFields = Arrays.asList(
                fieldPath + ".line",
                fieldPath + ".city", 
                fieldPath + ".district", 
                fieldPath + ".state", 
                fieldPath + ".postalCode", 
                fieldPath + ".country"
            );
            logger.info("🔍 StringSearchHelper: Expanded nested address field '{}' to: {}", fieldPath, expandedFields);
            return expandedFields;
        }
        
        if (fieldPath.endsWith(".telecom")) {
            List<String> expandedFields = Arrays.asList(fieldPath + ".value");
            logger.info("🔍 StringSearchHelper: Expanded nested telecom field '{}' to: {}", fieldPath, expandedFields);
            return expandedFields;
        }
        
        // For Organization-specific fields
        if ("alias".equals(fieldPath)) {
            // Organization.alias is already a simple string array, no expansion needed
            logger.info("🔍 StringSearchHelper: Field 'alias' needs no expansion");
            return Arrays.asList(fieldPath);
        }
        
        // Default: no expansion needed
        logger.info("🔍 StringSearchHelper: Field '{}' needs no expansion", fieldPath);
        return Arrays.asList(fieldPath);
    }
}

