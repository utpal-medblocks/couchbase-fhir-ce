package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.MatchQuery;
import com.couchbase.client.java.search.queries.DisjunctionQuery;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class TokenSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(TokenSearchHelper.class);

    public static SearchQuery buildTokenFTSQuery(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 TokenSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Extract the field name from the path (remove resourceType prefix)
                if (path != null && path.startsWith(resourceType + ".")) {
                    actualFieldName = path.substring(resourceType.length() + 1);
                    logger.info("🔍 TokenSearchHelper: Using field name: {}", actualFieldName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 TokenSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Parse the search value (format: system|code or just code)
        String[] parts = searchValue.split("\\|");
        String system = null;
        String code = null;

        if (parts.length == 2) {
            system = parts[0];
            code = parts[1];
        } else if (parts.length == 1) {
            code = parts[0];
        }

        // Get concept info to determine the type of field
        ConceptInfo conceptInfo = getConceptInfo(fhirContext, resourceType, actualFieldName);
        logger.info("🔍 TokenSearchHelper: paramName={}, conceptInfo={}", paramName, conceptInfo);

        if (conceptInfo.isPrimitive) {
            // Handle primitive types (boolean, string, etc.)
            return createPrimitiveQuery(code, actualFieldName);
        }

        // Get sub-fields for complex token parameters using HAPI reflection
        List<String> subFields = getSubFields(fhirContext, resourceType, actualFieldName);
        
        if (subFields.isEmpty()) {
            logger.warn("🔍 TokenSearchHelper: No sub-fields found for paramName={}, using base field: {}", paramName, actualFieldName);
            subFields.add(actualFieldName);
        }

        logger.info("🔍 TokenSearchHelper: paramName={}, fhirPath={}, subFields={}", paramName, actualFieldName, subFields);

        List<SearchQuery> queries = new ArrayList<>();

        // Build queries for each sub-field
        for (String field : subFields) {
            if (code != null && !code.isEmpty()) {
                queries.add(SearchQuery.match(code).field(field));
            }

            if (system != null && !system.isEmpty()) {
                // For system, we need to match the system field
                String systemField = field.replace(".code", ".system");
                if (!systemField.equals(field)) {
                    queries.add(SearchQuery.match(system).field(systemField));
                }
            }
        }

        if (queries.isEmpty()) {
            return null;
        }

        if (queries.size() == 1) {
            return queries.get(0);
        }

        // Multiple conditions: use AND (conjunction) for more precise matching
        return SearchQuery.conjuncts(queries.toArray(new SearchQuery[0]));
    }

    /**
     * Create appropriate query for primitive values (boolean, string, etc.)
     */
    private static SearchQuery createPrimitiveQuery(String value, String ftsFieldPath) {
        // Check if the value is a boolean
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            boolean boolValue = Boolean.parseBoolean(value);
            return SearchQuery.booleanField(boolValue).field(ftsFieldPath);
        }
        
        // Default to match query for strings
        return SearchQuery.match(value).field(ftsFieldPath);
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

    private static boolean skipCommonIgnoredFields(String name) {
        return name.equals("id") || name.equals("extension") || name.equals("period") || name.equals("use");
    }

    /**
     * Get concept info to determine the type of field
     */
    private static ConceptInfo getConceptInfo(FhirContext ctx, String resourceType, String fieldName) {
        boolean isCodableConcept = false;
        boolean isArray = false;
        boolean isPrimitive = false;
        
        try {
            BaseRuntimeElementCompositeDefinition<?> resourceDef =
                    (BaseRuntimeElementCompositeDefinition<?>) ctx.getResourceDefinition(resourceType);
            BaseRuntimeChildDefinition fieldChild = resourceDef.getChildByName(fieldName);

            if (fieldChild != null) {
                if (fieldChild.getMax() == -1) {
                    isArray = true;
                }
                
                BaseRuntimeElementDefinition<?> fieldType = fieldChild.getChildByName(fieldName);
                if (fieldType != null) {
                    if (fieldType.getImplementingClass().getSimpleName().equalsIgnoreCase("CodeableConcept")) {
                        isCodableConcept = true;
                    }
                    
                    if (fieldType.isStandardType() && !(fieldType instanceof BaseRuntimeElementCompositeDefinition<?>)) {
                        isPrimitive = true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get concept info for field {}: {}", fieldName, e.getMessage());
        }
        
        return new ConceptInfo(isCodableConcept, isArray, isPrimitive);
    }

    /**
     * Simple class to hold concept information
     */
    private static class ConceptInfo {
        public final boolean isCodableConcept;
        public final boolean isArray;
        public final boolean isPrimitive;
        
        public ConceptInfo(boolean isCodableConcept, boolean isArray, boolean isPrimitive) {
            this.isCodableConcept = isCodableConcept;
            this.isArray = isArray;
            this.isPrimitive = isPrimitive;
        }
        
        @Override
        public String toString() {
            return String.format("ConceptInfo{isCodableConcept=%s, isArray=%s, isPrimitive=%s}", 
                               isCodableConcept, isArray, isPrimitive);
        }
    }
}
