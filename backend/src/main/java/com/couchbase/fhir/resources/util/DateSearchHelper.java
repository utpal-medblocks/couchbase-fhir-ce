package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.DateRangeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;

public class DateSearchHelper {

    private static final Logger logger = LoggerFactory.getLogger(DateSearchHelper.class);

    /**
     * Build date queries - returns multiple queries for union expressions (like onsetDateTime | onsetPeriod)
     */
    public static List<SearchQuery> buildDateFTSQueries(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        if (searchValue == null || searchValue.isEmpty()) {
            return new ArrayList<>();
        }

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

        // Get the HAPI search parameter to check for union expressions
        List<SearchQuery> queries = new ArrayList<>();
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null && searchParam.getPath() != null) {
                FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(searchParam.getPath());
                
                if (parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
                    logger.info("🔍 DateSearchHelper: Building multiple queries for union with {} alternatives", parsed.getFieldPaths().size());
                    
                    // Create a query for each alternative in the union
                    for (String fieldPath : parsed.getFieldPaths()) {
                        SearchQuery query = buildDateQueryForField(fieldPath, start, end, inclusiveStart, inclusiveEnd);
                        if (query != null) {
                            queries.add(query);
                            logger.info("🔍 DateSearchHelper: Added query for field: {}", fieldPath);
                        }
                    }
                } else {
                    // Single field - use primary field path
                    String fieldPath = parsed.getPrimaryFieldPath();
                    if (fieldPath == null) {
                        fieldPath = paramName; // Fallback
                    }
                    
                    // For DATE parameters, suggest DateTime field for choice types
                    String suggestedField = FHIRPathParser.suggestDateTimeFieldPath(searchParam.getPath());
                    if (suggestedField != null && !suggestedField.equals(fieldPath)) {
                        logger.info("🔍 DateSearchHelper: Choice type detected, using DateTime field: {} -> {}", fieldPath, suggestedField);
                        fieldPath = suggestedField;
                    }
                    
                    // Check if this is a Period field using HAPI reflection
                    boolean isPeriodField = fieldPath.endsWith("Period") || isPeriodType(fhirContext, resourceType, fieldPath);
                    if (isPeriodField) {
                        // Handle Period fields by adding .start or .end
                        if (start != null && end == null) {
                            fieldPath = fieldPath + ".start";
                            logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", fieldPath);
                        } else if (end != null && start == null) {
                            fieldPath = fieldPath + ".end";
                            logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", fieldPath);
                        } else {
                            // For exact dates or range queries, use start
                            fieldPath = fieldPath + ".start";
                            logger.info("🔍 DateSearchHelper: Period field detected, using start for exact/range date: {}", fieldPath);
                        }
                    }
                    
                    SearchQuery query = buildDateQueryForField(fieldPath, start, end, inclusiveStart, inclusiveEnd);
                    if (query != null) {
                        queries.add(query);
                    }
                }
            } else {
                // Fallback - create single query with param name
                SearchQuery query = buildDateQueryForField(paramName, start, end, inclusiveStart, inclusiveEnd);
                if (query != null) {
                    queries.add(query);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to parse union expression, using single query: {}", e.getMessage());
            SearchQuery query = buildDateQueryForField(paramName, start, end, inclusiveStart, inclusiveEnd);
            if (query != null) {
                queries.add(query);
            }
        }

        return queries;
    }

    /**
     * Build a single date query for a specific field
     */
    private static SearchQuery buildDateQueryForField(String fieldPath, String start, String end, boolean inclusiveStart, boolean inclusiveEnd) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            return null;
        }

        String dateField = fieldPath;
        
        // Handle Period fields by adding .start or .end
        if (fieldPath.endsWith("Period")) {
            if (start != null) {
                dateField = fieldPath + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", dateField);
            } else if (end != null) {
                dateField = fieldPath + ".end";
                logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", dateField);
            } else {
                // For exact dates on Period fields, use start
                dateField = fieldPath + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start for exact date: {}", dateField);
            }
        } else {
            logger.info("🔍 DateSearchHelper: Using DateTime field as-is: {}", dateField);
        }

        DateRangeQuery query = SearchQuery.dateRange().field(dateField);
        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        if (searchValue == null || searchValue.isEmpty()) {
            return null;
        }

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

        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 DateSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Use FHIRPathParser to handle complex expressions
                if (path != null) {
                    FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(path);
                    actualFieldName = parsed.getPrimaryFieldPath();
                    
                    if (actualFieldName == null) {
                        logger.warn("🔍 DateSearchHelper: Could not parse field path from: {}", path);
                        // Fallback to simple extraction
                        if (path.startsWith(resourceType + ".")) {
                            actualFieldName = path.substring(resourceType.length() + 1);
                        } else {
                            actualFieldName = paramName;
                        }
                    }
                    
                    // For DATE parameters, suggest DateTime field for choice types
                    String suggestedField = FHIRPathParser.suggestDateTimeFieldPath(path);
                    if (suggestedField != null && !suggestedField.equals(actualFieldName)) {
                        logger.info("🔍 DateSearchHelper: Choice type detected, using DateTime field: {} -> {}", actualFieldName, suggestedField);
                        actualFieldName = suggestedField;
                    }
                    
                    logger.info("🔍 DateSearchHelper: Parsed field name: {}", actualFieldName);
                } else {
                    logger.warn("🔍 DateSearchHelper: HAPI path is null for paramName={}", paramName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Handle special cases (like parent project)
        if (paramName.equalsIgnoreCase("death-date")) {
            actualFieldName = "deceasedDateTime";
        }

        logger.info("🔍 DateSearchHelper: paramName={}, fhirPath={}, start={}, end={}", 
                   paramName, actualFieldName, start, end);

        // Use FHIRPathParser to get parsed expression for proper field handling
        FHIRPathParser.ParsedExpression parsed = null;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null && searchParam.getPath() != null) {
                parsed = FHIRPathParser.parse(searchParam.getPath());
            }
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Could not re-parse expression: {}", e.getMessage());
        }
        
        // Handle union expressions (multiple field paths)
        if (parsed != null && parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
            logger.info("🔍 DateSearchHelper: Union expression detected with {} alternatives", parsed.getFieldPaths().size());
            
            // For union expressions, we need to create a disjunctive query
            // For now, use the first DateTime field if available, otherwise the first field
            String selectedField = actualFieldName;
            for (String fieldPath : parsed.getFieldPaths()) {
                if (fieldPath.endsWith("DateTime")) {
                    selectedField = fieldPath;
                    logger.info("🔍 DateSearchHelper: Selected DateTime field from union: {}", selectedField);
                    break;
                }
            }
            
            if (selectedField != null && selectedField.equals(actualFieldName) && !parsed.getFieldPaths().isEmpty()) {
                selectedField = parsed.getFieldPaths().get(0);
                logger.info("🔍 DateSearchHelper: Selected first field from union: {}", selectedField);
            }
            
            actualFieldName = selectedField;
        }
        
        // Check if this is a Period field and adjust field path accordingly
        String dateField = actualFieldName;
        
        // Check both field name ending and actual HAPI type
        boolean isPeriodField = (actualFieldName != null) && 
                               (actualFieldName.endsWith("Period") || isPeriodType(fhirContext, resourceType, actualFieldName));
        
        if (isPeriodField) {
            if (start != null) {
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", dateField);
            } else if (end != null) {
                dateField = actualFieldName + ".end";
                logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", dateField);
            } else {
                // For exact dates on Period fields, use start
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start for exact date: {}", dateField);
            }
        } else {
            logger.info("🔍 DateSearchHelper: Using field as-is: {}", dateField);
        }

        DateRangeQuery query = SearchQuery.dateRange().field(dateField);
        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, List<String> searchValues) {
        if (searchValues == null || searchValues.isEmpty()) {
            return null;
        }

        if (searchValues.size() == 1) {
            return buildDateFTS(fhirContext, resourceType, paramName, searchValues.get(0));
        }

        // Handle multiple date values (e.g., birthdate=ge1987-01-01&birthdate=le1987-12-31)
        String start = null;
        String end = null;
        boolean inclusiveStart = true;
        boolean inclusiveEnd = true;

        for (String searchValue : searchValues) {
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
        }

        // Get the actual FHIR path from HAPI search parameter
        String actualFieldName = paramName;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null) {
                String path = searchParam.getPath();
                logger.info("🔍 DateSearchHelper: paramName={}, HAPI path={}", paramName, path);
                
                // Use FHIRPathParser to handle complex expressions
                if (path != null) {
                    FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(path);
                    actualFieldName = parsed.getPrimaryFieldPath();
                    
                    if (actualFieldName == null) {
                        logger.warn("🔍 DateSearchHelper: Could not parse field path from: {}", path);
                        // Fallback to simple extraction
                        if (path.startsWith(resourceType + ".")) {
                            actualFieldName = path.substring(resourceType.length() + 1);
                        } else {
                            actualFieldName = paramName;
                        }
                    }
                    
                    // For DATE parameters, suggest DateTime field for choice types
                    String suggestedField = FHIRPathParser.suggestDateTimeFieldPath(path);
                    if (suggestedField != null && !suggestedField.equals(actualFieldName)) {
                        logger.info("🔍 DateSearchHelper: Choice type detected, using DateTime field: {} -> {}", actualFieldName, suggestedField);
                        actualFieldName = suggestedField;
                    }
                    
                    logger.info("🔍 DateSearchHelper: Parsed field name: {}", actualFieldName);
                } else {
                    logger.warn("🔍 DateSearchHelper: HAPI path is null for paramName={}", paramName);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to get HAPI path for paramName={}, using paramName as field: {}", paramName, e.getMessage());
        }

        // Handle special cases (like parent project)
        if (paramName.equalsIgnoreCase("death-date")) {
            actualFieldName = "deceasedDateTime";
        }

        logger.info("🔍 DateSearchHelper: paramName={}, fhirPath={}, start={}, end={}", 
                   paramName, actualFieldName, start, end);

        // Use FHIRPathParser to get parsed expression for proper field handling
        FHIRPathParser.ParsedExpression parsed = null;
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null && searchParam.getPath() != null) {
                parsed = FHIRPathParser.parse(searchParam.getPath());
            }
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Could not re-parse expression: {}", e.getMessage());
        }
        
        // Handle union expressions (multiple field paths)
        if (parsed != null && parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
            logger.info("🔍 DateSearchHelper: Union expression detected with {} alternatives", parsed.getFieldPaths().size());
            
            // For union expressions, use the first DateTime field if available, otherwise the first field
            String selectedField = actualFieldName;
            for (String fieldPath : parsed.getFieldPaths()) {
                if (fieldPath.endsWith("DateTime")) {
                    selectedField = fieldPath;
                    logger.info("🔍 DateSearchHelper: Selected DateTime field from union: {}", selectedField);
                    break;
                }
            }
            
            if (selectedField != null && selectedField.equals(actualFieldName) && !parsed.getFieldPaths().isEmpty()) {
                selectedField = parsed.getFieldPaths().get(0);
                logger.info("🔍 DateSearchHelper: Selected first field from union: {}", selectedField);
            }
            
            actualFieldName = selectedField;
        }

        // Check if this is a Period field and adjust field path accordingly
        String dateField = actualFieldName;
        
        if (actualFieldName.endsWith("Period")) {
            if (start != null) {
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start: {}", dateField);
            } else if (end != null) {
                dateField = actualFieldName + ".end";
                logger.info("🔍 DateSearchHelper: Period field detected, using end: {}", dateField);
            } else {
                // For exact dates on Period fields, use start
                dateField = actualFieldName + ".start";
                logger.info("🔍 DateSearchHelper: Period field detected, using start for exact date: {}", dateField);
            }
        } else {
            logger.info("🔍 DateSearchHelper: Using field as-is: {}", dateField);
        }

        DateRangeQuery query = SearchQuery.dateRange().field(dateField);

        if (start != null) query = query.start(start, inclusiveStart);
        if (end != null) query = query.end(end, inclusiveEnd);

        return query;
    }

    /**
     * Check if a field is a Period type using HAPI reflection
     * Handles nested paths like "context.period"
     */
    private static boolean isPeriodType(FhirContext fhirContext, String resourceType, String fieldPath) {
        try {
            ca.uhn.fhir.context.RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            String[] pathParts = fieldPath.split("\\.");
            
            ca.uhn.fhir.context.BaseRuntimeElementDefinition<?> currentDef = resourceDef;
            
            // Navigate through each part of the path
            for (String part : pathParts) {
                if (currentDef instanceof ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition) {
                    ca.uhn.fhir.context.BaseRuntimeChildDefinition childDef = 
                        ((ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition<?>) currentDef).getChildByName(part);
                    if (childDef != null) {
                        currentDef = childDef.getChildByName(part);
                    } else {
                        logger.debug("🔍 DateSearchHelper: Could not find field part '{}' in path '{}'", part, fieldPath);
                        return false;
                    }
                } else {
                    logger.debug("🔍 DateSearchHelper: Field part '{}' is not composite in path '{}'", part, fieldPath);
                    return false;
                }
            }
            
            if (currentDef != null) {
                String className = currentDef.getImplementingClass().getSimpleName();
                boolean isPeriod = "Period".equalsIgnoreCase(className);
                logger.debug("🔍 DateSearchHelper: Field {} has type: {} (isPeriod: {})", fieldPath, className, isPeriod);
                return isPeriod;
            }
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Failed to check if field {} is a Period type: {}", fieldPath, e.getMessage());
        }
        return false;
    }
}
