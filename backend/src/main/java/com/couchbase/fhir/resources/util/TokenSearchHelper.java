package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
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
import java.util.Arrays;
import java.util.stream.Collectors;

public class TokenSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(TokenSearchHelper.class);

    public static SearchQuery buildTokenFTSQuery(FhirContext fhirContext,
                                                 String resourceType,
                                                 String paramName,
                                                 String tokenValue) {

        TokenParam token = new TokenParam(tokenValue);
        boolean isMultipleValues = token.code.contains(",");
        
        logger.info("🔍 TokenSearchHelper: paramName={}, tokenValue={}, parsed system={}, code={}", 
                    paramName, tokenValue, token.system, token.code);
    
        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);
        String path = searchParam.getPath();
        
        logger.info("🔍 TokenSearchHelper: HAPI path={}, paramType={}", 
                    path, searchParam.getParamType());
        
        ConceptInfo conceptInfo = getConceptInfo(path, resourceType, def);
        logger.info("🔍 TokenSearchHelper: conceptInfo={}", conceptInfo);
    
        String ftsFieldPath = toFTSFieldPath(path, resourceType, conceptInfo.isCodableConcept,
                conceptInfo.isArray, conceptInfo.isPrimitive);
        logger.info("🔍 TokenSearchHelper: ftsFieldPath={}", ftsFieldPath);
        if (conceptInfo.isPrimitive) {
            // Primitive tokens like gender, id, status, etc.
            if (isMultipleValues) {
                List<SearchQuery> termQueries = Arrays.stream(token.code.split(","))
                        .map(String::trim)
                        .map(val -> createPrimitiveQuery(val, ftsFieldPath))
                        .collect(Collectors.toList());
                return SearchQuery.disjuncts(termQueries.toArray(new SearchQuery[0]));
            } else {
                return createPrimitiveQuery(token.code, ftsFieldPath);
            }
        }

        // Handle CodeableConcept arrays or single
        if (conceptInfo.isCodableConcept && conceptInfo.isArray) {
            // Example: ANY cat IN ... SATISFIES ANY coding...
            return buildCodeableConceptQuery(ftsFieldPath, token);
        } else if (conceptInfo.isCodableConcept) {
            // Single CodeableConcept
            return buildCodeableConceptQuery(ftsFieldPath, token);
        } else {
            // Other complex type with system/value
            return buildSystemValueQuery(ftsFieldPath, token);
        }
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

    private static SearchQuery buildCodeableConceptQuery(String ftsFieldPath, TokenParam token) {
        // For FTS index: field might be like "name.coding.code" and "name.coding.system"
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(ftsFieldPath + ".system"),
                    SearchQuery.match(token.code).field(ftsFieldPath + ".code")
            );
        } else {
            return SearchQuery.match(token.code).field(ftsFieldPath + ".code");
        }
    }

    private static SearchQuery buildSystemValueQuery(String ftsFieldPath, TokenParam token) {
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(ftsFieldPath + ".system"),
                    SearchQuery.match(token.code).field(ftsFieldPath + ".value")
            );
        } else {
            return SearchQuery.match(token.code).field(ftsFieldPath + ".value");
        }
    }

    public static ConceptInfo getConceptInfo(String path, String resourceType, RuntimeResourceDefinition def) {
        boolean isCodableConcept = false;
        boolean isArray = false;
        boolean isPrimitive = false;
        BaseRuntimeElementDefinition<?> current = def;
        
        try {
            String fhirPath = path.replaceFirst("^" + resourceType + "\\.", "");
            String[] pathParts = fhirPath.split("\\.");
            
            for (String part : pathParts) {
                if (def != null) {
                    BaseRuntimeChildDefinition child = ((BaseRuntimeElementCompositeDefinition<?>) def).getChildByName(part);
                    if (child != null) {
                        if (child.getMax() == -1) {
                            isArray = true;
                        }
                        if (child.getChildByName(part).getImplementingClass().getSimpleName().equalsIgnoreCase("CodeableConcept")) {
                            isCodableConcept = true;
                        }

                        current = child.getChildByName(part);
                        if (current.isStandardType() && !(current instanceof BaseRuntimeElementCompositeDefinition)) {
                            isPrimitive = true;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return new ConceptInfo(isCodableConcept, isArray, isPrimitive);
    }

    /**
     * Convert FHIRPath to the FTS field path used in the Couchbase index mapping.
     */
    private static String toFTSFieldPath(String fhirPath,
                                         String resourceType,
                                         boolean codableConcept,
                                         boolean isArray,
                                         boolean isPrimitive) {
        if (fhirPath == null) {
            throw new IllegalArgumentException("FHIRPath is null");
        }

        if (!fhirPath.startsWith(resourceType + ".") && !fhirPath.startsWith("Resource.")) {
            throw new IllegalArgumentException("Invalid FHIRPath: " + fhirPath);
        }
        String subPath;
        if (fhirPath.contains("Resource")) {
            subPath = fhirPath.substring(9);
        } else {
            subPath = fhirPath.substring(resourceType.length() + 1);
        }
        
        String ftsPath = subPath
                .replace(".coding", ".coding") // Keep .coding for FTS mapping
                .replace(".value", ".value")
                .replace(".code", ".code")
                .replace(".system", ".system");

        if (isPrimitive) {
            return ftsPath;
        }

        if (codableConcept) {
            // For CodeableConcepts, we always need to add .coding
            // Whether it's a single CodeableConcept or an array of CodeableConcepts
            ftsPath += ".coding";
        }
        return ftsPath;
    }
}