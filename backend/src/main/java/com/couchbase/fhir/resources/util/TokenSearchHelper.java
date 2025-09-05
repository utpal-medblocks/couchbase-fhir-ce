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

    /**
     * Build TOKEN query from FHIR expression (for US Core and custom parameters)
     */
    public static SearchQuery buildTokenFTSQueryFromExpression(FhirContext fhirContext,
                                                              String resourceType,
                                                              String expression,
                                                              String tokenValue) {
        logger.info("🔍 TokenSearchHelper: Building query from expression: {}", expression);
        
        // Parse the expression to get field path
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(expression);
        String fieldPath = parsed.getPrimaryFieldPath();
        
        if (fieldPath == null) {
            logger.warn("🔍 TokenSearchHelper: Could not extract field path from: {}", expression);
            return SearchQuery.match(tokenValue).field("unknown");
        }
        
        // Parse token value
        TokenParam token = new TokenParam(tokenValue);
        
        // Introspect field type and build appropriate query
        return buildTokenQueryForFieldPath(fhirContext, resourceType, fieldPath, token);
    }

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

    /**
     * Build TOKEN query for a specific field path using comprehensive type introspection
     */
    private static SearchQuery buildTokenQueryForFieldPath(FhirContext fhirContext, String resourceType, 
                                                          String fieldPath, TokenParam token) {
        // Introspect the field type
        TokenFieldType fieldType = introspectTokenFieldType(fhirContext, resourceType, fieldPath);
        logger.info("🔍 TokenSearchHelper: Field {} resolved to type: {}", fieldPath, fieldType);
        
        switch (fieldType) {
            case CODEABLE_CONCEPT:
                return buildCodeableConceptQuery(fieldPath + ".coding", token);
                
            case CODING:
                return buildCodingQuery(fieldPath, token);
                
            case IDENTIFIER:
                return buildIdentifierQuery(fieldPath, token);
                
            case PRIMITIVE_CODE:
            case PRIMITIVE_BOOLEAN:
            case PRIMITIVE_URI:
            case PRIMITIVE_STRING:
                return buildPrimitiveQuery(fieldPath, token);
                
            default:
                logger.warn("🔍 TokenSearchHelper: Unknown field type for {}, using as-is", fieldPath);
                return SearchQuery.match(token.code).field(fieldPath);
        }
    }

    /**
     * Introspect field type using HAPI reflection
     */
    private static TokenFieldType introspectTokenFieldType(FhirContext fhirContext, String resourceType, String fieldPath) {
        try {
            ca.uhn.fhir.context.RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            String[] pathParts = fieldPath.split("\\.");
            
            ca.uhn.fhir.context.BaseRuntimeElementDefinition<?> currentDef = resourceDef;
            
            for (String part : pathParts) {
                if (currentDef instanceof ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition) {
                    ca.uhn.fhir.context.BaseRuntimeChildDefinition childDef = 
                        ((ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition<?>) currentDef).getChildByName(part);
                    if (childDef != null) {
                        currentDef = childDef.getChildByName(part);
                    } else {
                        return TokenFieldType.UNKNOWN;
                    }
                } else {
                    return TokenFieldType.UNKNOWN;
                }
            }
            
            if (currentDef != null) {
                String className = currentDef.getImplementingClass().getSimpleName();
                return mapClassNameToFieldType(className);
            }
        } catch (Exception e) {
            logger.debug("🔍 TokenSearchHelper: Failed to introspect field {}: {}", fieldPath, e.getMessage());
        }
        return TokenFieldType.UNKNOWN;
    }

    /**
     * Map HAPI class names to our field types
     */
    private static TokenFieldType mapClassNameToFieldType(String className) {
        switch (className) {
            case "CodeableConcept":
                return TokenFieldType.CODEABLE_CONCEPT;
            case "Coding":
                return TokenFieldType.CODING;
            case "Identifier":
                return TokenFieldType.IDENTIFIER;
            case "CodeType":
            case "Code":
                return TokenFieldType.PRIMITIVE_CODE;
            case "BooleanType":
            case "Boolean":
                return TokenFieldType.PRIMITIVE_BOOLEAN;
            case "UriType":
            case "CanonicalType":
            case "OidType":
            case "Uri":
            case "Canonical":
            case "Oid":
                return TokenFieldType.PRIMITIVE_URI;
            case "StringType":
            case "String":
                return TokenFieldType.PRIMITIVE_STRING;
            default:
                return TokenFieldType.UNKNOWN;
        }
    }

    /**
     * Build query for Coding type (path.code + path.system)
     */
    private static SearchQuery buildCodingQuery(String fieldPath, TokenParam token) {
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(fieldPath + ".system"),
                    SearchQuery.match(token.code).field(fieldPath + ".code")
            );
        } else {
            return SearchQuery.match(token.code).field(fieldPath + ".code");
        }
    }

    /**
     * Build query for Identifier type (path.value + path.system)
     */
    private static SearchQuery buildIdentifierQuery(String fieldPath, TokenParam token) {
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(fieldPath + ".system"),
                    SearchQuery.match(token.code).field(fieldPath + ".value")
            );
        } else {
            return SearchQuery.match(token.code).field(fieldPath + ".value");
        }
    }

    /**
     * Build query for primitive types (exact match on field)
     */
    private static SearchQuery buildPrimitiveQuery(String fieldPath, TokenParam token) {
        // For primitives, ignore system and just match the code/value
        return SearchQuery.match(token.code).field(fieldPath);
    }

    /**
     * Enum for TOKEN field types
     */
    private enum TokenFieldType {
        CODEABLE_CONCEPT,
        CODING,
        IDENTIFIER,
        PRIMITIVE_CODE,
        PRIMITIVE_BOOLEAN,
        PRIMITIVE_URI,
        PRIMITIVE_STRING,
        UNKNOWN
    }
}