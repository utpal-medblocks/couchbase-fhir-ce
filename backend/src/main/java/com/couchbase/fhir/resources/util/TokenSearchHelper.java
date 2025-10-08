package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import com.couchbase.client.java.search.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class TokenSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(TokenSearchHelper.class);

    /**
     * Build TOKEN query - main entry point handling both HAPI parameters and direct expressions
     */
    public static SearchQuery buildTokenFTSQuery(FhirContext fhirContext,
                                                String resourceType,
                                                String paramName,
                                                String tokenValue) {
        try {
            // Get HAPI search parameter
            RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
            RuntimeSearchParam searchParam = def.getSearchParam(paramName);
            
            if (searchParam != null && searchParam.getPath() != null) {
                logger.debug("🔍 TokenSearchHelper: HAPI path={}", searchParam.getPath());
                return buildTokenFTSQueryFromExpression(fhirContext, resourceType, searchParam.getPath(), tokenValue);
            } else {
                // Fallback for parameters without HAPI definition
                logger.warn("🔍 TokenSearchHelper: No HAPI path found for {}, using parameter name", paramName);
                return buildTokenQueryForField(resourceType, paramName, tokenValue);
            }
        } catch (Exception e) {
            logger.warn("🔍 TokenSearchHelper: Failed to get HAPI path for {}: {}", paramName, e.getMessage());
            return buildTokenQueryForField(resourceType, paramName, tokenValue);
        }
    }

    /**
     * Build TOKEN query from FHIR expression (for US Core and custom parameters)
     */
    public static SearchQuery buildTokenFTSQueryFromExpression(FhirContext fhirContext,
                                                              String resourceType,
                                                              String expression,
                                                              String tokenValue) {
        logger.debug("🔍 TokenSearchHelper: Building query from expression: {}", expression);

        // Parse the expression to get field path
        FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(expression);
        String fieldPath = parsed.getPrimaryFieldPath();
        
        if (fieldPath == null) {
            logger.warn("🔍 TokenSearchHelper: Could not extract field path from: {}", expression);
            return SearchQuery.match(tokenValue).field("unknown");
        }
        
        // Introspect field type and build appropriate query
        return buildTokenQueryForField(fhirContext, resourceType, fieldPath, tokenValue);
    }

    /**
     * Build TOKEN query for a specific field path with type introspection
     */
    private static SearchQuery buildTokenQueryForField(FhirContext fhirContext, String resourceType, String fieldPath, String tokenValue) {
        // Parse token value
        TokenParam token = new TokenParam(tokenValue);
        
        // Handle comma-separated values (e.g., "DF,EM,SO")
        if (token.code.contains(",")) {
            String[] codes = token.code.split(",");
            List<SearchQuery> queries = new ArrayList<>();
            
            for (String code : codes) {
                TokenParam singleToken = new TokenParam(token.system != null ? token.system + "|" + code.trim() : code.trim());
                SearchQuery query = buildSingleTokenQuery(fhirContext, resourceType, fieldPath, singleToken);
                if (query != null) {
                    queries.add(query);
                }
            }

            logger.debug("🔍 TokenSearchHelper: Created {} disjunctive queries for comma-separated values", queries.size());
            return queries.isEmpty() ? null : SearchQuery.disjuncts(queries.toArray(new SearchQuery[0]));
        }
        
        // Single value
        return buildSingleTokenQuery(fhirContext, resourceType, fieldPath, token);
    }

    /**
     * Simple fallback for fields without HAPI introspection
     */
    private static SearchQuery buildTokenQueryForField(String resourceType, String fieldPath, String tokenValue) {
        TokenParam token = new TokenParam(tokenValue);
        
        // Handle comma-separated values
        if (token.code.contains(",")) {
            String[] codes = token.code.split(",");
            List<SearchQuery> queries = new ArrayList<>();
            
            for (String code : codes) {
                queries.add(SearchQuery.match(code.trim()).field(fieldPath));
            }
            
            return SearchQuery.disjuncts(queries.toArray(new SearchQuery[0]));
        }
        
        // Simple match query
        return SearchQuery.match(token.code).field(fieldPath);
    }

    /**
     * Build a single token query based on HAPI field type introspection
     */
    private static SearchQuery buildSingleTokenQuery(FhirContext fhirContext, String resourceType, String fieldPath, TokenParam token) {
        try {
            // Introspect field type using HAPI
            String fieldType = introspectFieldType(fhirContext, resourceType, fieldPath);
            logger.debug("🔍 TokenSearchHelper: introspectFieldType: {}", fieldType);
            switch (fieldType.toLowerCase()) {
                case "codeableconcept":
                    return buildCodeableConceptQuery(fieldPath, token);
                case "coding":
                    return buildCodingQuery(fieldPath, token);
                case "identifier":
                    return buildIdentifierQuery(fieldPath, token);
                case "contactpoint":
                    return buildContactPointQuery(fieldPath, token);
                case "booleantype":
                    return buildBooleanQuery(fieldPath, token);
                case "code":
                case "boolean":
                case "string":
                case "uri":
                case "canonical":
                case "oid":
                    return buildPrimitiveQuery(fieldPath, token);
                default:
                    logger.warn("🔍 TokenSearchHelper: Unknown field type '{}' for {}, using primitive query", fieldType, fieldPath);
                    return buildPrimitiveQuery(fieldPath, token);
            }
        } catch (Exception e) {
            logger.warn("🔍 TokenSearchHelper: Failed to introspect field type for {}: {}", fieldPath, e.getMessage());
            return buildPrimitiveQuery(fieldPath, token);
        }
    }

    /**
     * Introspect FHIR field type using HAPI reflection
     */
    private static String introspectFieldType(FhirContext fhirContext, String resourceType, String fieldPath) {
        try {
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            String[] pathParts = fieldPath.split("\\.");
            
            BaseRuntimeElementDefinition<?> currentDef = resourceDef;
            
            // Navigate through each part of the path
            for (String part : pathParts) {
                if (currentDef instanceof BaseRuntimeElementCompositeDefinition) {
                    BaseRuntimeChildDefinition childDef = 
                        ((BaseRuntimeElementCompositeDefinition<?>) currentDef).getChildByName(part);
                    if (childDef != null) {
                        currentDef = childDef.getChildByName(part);
                    } else {
                        logger.debug("🔍 TokenSearchHelper: Could not find field part '{}' in path '{}'", part, fieldPath);
                        return "unknown";
                    }
                } else {
                    logger.debug("🔍 TokenSearchHelper: Field part '{}' is not composite in path '{}'", part, fieldPath);
                    return "unknown";
                }
            }
            
            if (currentDef != null) {
                String className = currentDef.getImplementingClass().getSimpleName();
                logger.debug("🔍 TokenSearchHelper: Field {} has type: {}", fieldPath, className);
                return className;
            }
        } catch (Exception e) {
            logger.debug("🔍 TokenSearchHelper: Failed to introspect field type for {}: {}", fieldPath, e.getMessage());
        }
        return "unknown";
    }

    /**
     * Build query for CodeableConcept type (path.coding.code + path.coding.system)
     */
    private static SearchQuery buildCodeableConceptQuery(String fieldPath, TokenParam token) {
        if (token.system != null) {
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(fieldPath + ".coding.system"),
                    SearchQuery.match(token.code).field(fieldPath + ".coding.code")
            );
        } else {
            return SearchQuery.match(token.code).field(fieldPath + ".coding.code");
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
     * Build query for ContactPoint type (path.value + path.system)
     * For telecom searches like Patient.telecom
     */
    private static SearchQuery buildContactPointQuery(String fieldPath, TokenParam token) {
        if (token.system != null) {
            // Search with both system (phone/email) and value
            return SearchQuery.conjuncts(
                    SearchQuery.match(token.system).field(fieldPath + ".system"),
                    SearchQuery.match(token.code).field(fieldPath + ".value")
            );
        } else {
            // Search just the value field (phone number, email address)
            return SearchQuery.match(token.code).field(fieldPath + ".value");
        }
    }

    /**
     * Build query for boolean types (true/false values)
     */
    private static SearchQuery buildBooleanQuery(String fieldPath, TokenParam token) {
        // For boolean fields, we need to match the actual boolean value, not string representation
        boolean boolValue = Boolean.parseBoolean(token.code);
        logger.debug("🔍 TokenSearchHelper: Building boolean query for field '{}' with value: {} (parsed as {})", 
                    fieldPath, token.code, boolValue);
        
        // Use proper boolean field query for FTS boolean field handling
        return SearchQuery.booleanField(boolValue).field(fieldPath);
    }

    /**
     * Build query for primitive types (exact match on field)
     */
    private static SearchQuery buildPrimitiveQuery(String fieldPath, TokenParam token) {
        // For primitives, ignore system and just match the code/value
        return SearchQuery.match(token.code).field(fieldPath);
    }

    /**
     * Token parameter parser - handles system|code format
     */
    private static class TokenParam {
        final String system;
        final String code;

        TokenParam(String tokenValue) {
            if (tokenValue == null || tokenValue.isEmpty()) {
                this.system = null;
                this.code = "";
                return;
            }

            if (tokenValue.contains("|")) {
                String[] parts = tokenValue.split("\\|", 2);
                this.system = parts[0].isEmpty() ? null : parts[0];
                this.code = parts.length > 1 ? parts[1] : "";
            } else {
                this.system = null;
                this.code = tokenValue;
            }
        }
    }
}