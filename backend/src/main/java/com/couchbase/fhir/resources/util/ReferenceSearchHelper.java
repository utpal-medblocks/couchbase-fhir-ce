package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.MatchQuery;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Arrays;

public class ReferenceSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceSearchHelper.class);

    public static SearchQuery buildReferenceFTSQuery(FhirContext fhirContext, String resourceType, String paramName, String searchValue, RuntimeSearchParam searchParam) {
        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 ReferenceSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Use FHIRPathParser to handle complex expressions
                if (path != null) {
                    FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(path);
                    actualFieldName = parsed.getPrimaryFieldPath();
                    
                    if (actualFieldName == null) {
                        logger.warn("🔍 ReferenceSearchHelper: Could not parse field path from: {}", path);
                        // Fallback to manual parsing
                        if (path.startsWith(resourceType + ".")) {
                            actualFieldName = path.substring(resourceType.length() + 1);
                            int whereIndex = actualFieldName.indexOf(".where(");
                            if (whereIndex != -1) {
                                actualFieldName = actualFieldName.substring(0, whereIndex);
                            }
                        } else {
                            actualFieldName = paramName;
                        }
                    }
                    
                    logger.info("🔍 ReferenceSearchHelper: Parsed field name: {}", actualFieldName);
                } else {
                    logger.warn("🔍 ReferenceSearchHelper: HAPI path is null for paramName={}", paramName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 ReferenceSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Parse the reference value (format: ResourceType/id or just id)
        String[] parts = searchValue.split("/");
        String targetResourceType = null;
        String targetId = null;

        if (parts.length == 2) {
            targetResourceType = parts[0];
            targetId = parts[1];
        } else if (parts.length == 1) {
            targetId = parts[0];
        }

        if (targetId == null || targetId.isEmpty()) {
            logger.warn("🔍 ReferenceSearchHelper: Invalid reference format: {}", searchValue);
            return null;
        }

        // Get sub-fields for the reference parameter using HAPI reflection
        List<String> subFields = Arrays.asList(actualFieldName + ".reference");        
        if (subFields.isEmpty()) {
            logger.warn("🔍 ReferenceSearchHelper: No sub-fields found for paramName={}, using base field: {}", paramName, actualFieldName);
            subFields.add(actualFieldName);
        }

        // If no target resource type provided, try to determine it from HAPI using getTargets()
        if (targetResourceType == null && searchParam != null) {
            try {
                Set<String> targets = searchParam.getTargets();
                if (targets.size() == 1) {
                    targetResourceType = targets.iterator().next();
                    logger.info("🔍 ReferenceSearchHelper: Found single target type from HAPI: {}", targetResourceType);
                } else if (targets.size() > 1) {
                    // Ambiguous target types — can't infer
                    String errorMsg = String.format(
                        "The reference parameter '%s' is ambiguous. Expected format: 'ResourceType/id', e.g., 'Patient/123'. Allowed targets: %s",
                        paramName, targets
                    );
                    logger.error("🔍 ReferenceSearchHelper: {}", errorMsg);
                    throw new IllegalArgumentException(errorMsg);
                } else {
                    logger.warn("🔍 ReferenceSearchHelper: No target types found in HAPI for parameter: {}", paramName);
                }
            } catch (Exception e) {
                logger.warn("🔍 ReferenceSearchHelper: Failed to get target types from HAPI: {}", e.getMessage());
            }
        }

        logger.info("🔍 ReferenceSearchHelper: paramName={}, fhirPath={}, subFields={}", paramName, actualFieldName, subFields);

        // Build the reference value - always use full reference format
        String referenceValue = targetResourceType != null ? targetResourceType + "/" + targetId : targetId;
        logger.info("🔍 ReferenceSearchHelper: Final reference value: {}", referenceValue);
        
        List<SearchQuery> queries = new ArrayList<>();
        
        // Build queries for each sub-field
        for (String field : subFields) {
            queries.add(SearchQuery.match(referenceValue).field(field));
        }

        if (queries.isEmpty()) {
            return null;
        }

        if (queries.size() == 1) {
            return queries.get(0);
        }

        // Multiple sub-fields: use OR (disjunction) to match any of them
        return SearchQuery.disjuncts(queries.toArray(new SearchQuery[0]));
    }
}
