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
     * Build date FTS query - handles single search value
     */
    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        List<String> searchValues = searchValue != null ? List.of(searchValue) : List.of();
        return buildDateFTS(fhirContext, resourceType, paramName, searchValues);
    }

    /**
     * Build date FTS query - main implementation handling all cases
     */
    public static SearchQuery buildDateFTS(FhirContext fhirContext, String resourceType, String paramName, List<String> searchValues) {
        if (searchValues == null || searchValues.isEmpty()) {
            return null;
        }

        // Parse all search values to determine date range
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

        // Get field paths using HAPI introspection
        List<String> fieldPaths = getDateFieldPaths(fhirContext, resourceType, paramName);
        
        if (fieldPaths.size() == 1) {
            // Single field - create simple query
            return buildDateQueryForField(fieldPaths.get(0), start, end, inclusiveStart, inclusiveEnd);
        } else if (fieldPaths.size() > 1) {
            // Multiple fields - create disjunctive (OR) query for all fields
            // Period logic is now handled directly in buildDateQueryForField
            List<SearchQuery> queries = new ArrayList<>();
            for (String fieldPath : fieldPaths) {
                SearchQuery query = buildDateQueryForField(fieldPath, start, end, inclusiveStart, inclusiveEnd);
                if (query != null) {
                    queries.add(query);
                }
            }
            return queries.isEmpty() ? null : SearchQuery.disjuncts(queries.toArray(new SearchQuery[0]));
        }
        
        return null;
    }

    /**
     * Build date queries - returns multiple queries for union expressions (like onsetDateTime | onsetPeriod)
     * @deprecated Use buildDateFTS instead - this method is kept for backward compatibility
     */
    @Deprecated
    public static List<SearchQuery> buildDateFTSQueries(FhirContext fhirContext, String resourceType, String paramName, String searchValue) {
        SearchQuery query = buildDateFTS(fhirContext, resourceType, paramName, searchValue);
        return query != null ? List.of(query) : new ArrayList<>();
    }

    /**
     * Get all possible date field paths for a parameter using HAPI introspection
     */
    private static List<String> getDateFieldPaths(FhirContext fhirContext, String resourceType, String paramName) {
        try {
            ca.uhn.fhir.context.RuntimeSearchParam searchParam = fhirContext.getResourceDefinition(resourceType).getSearchParam(paramName);
            if (searchParam != null && searchParam.getPath() != null) {
                FHIRPathParser.ParsedExpression parsed = FHIRPathParser.parse(searchParam.getPath());
                
                if (parsed.isUnion() && parsed.getFieldPaths().size() > 1) {
                    // Union expression - return all field paths as-is (Period handling done in FHIRPath parsing)
                    return new ArrayList<>(parsed.getFieldPaths());
                } else {
                    // Single field - use choice type expansion (includes Period handling)
                    String fieldPath = parsed.getPrimaryFieldPath();
                    if (fieldPath == null) {
                        fieldPath = paramName;
                    }
                    
                    return expandDateChoicePaths(fhirContext, resourceType, fieldPath);
                }
            }
        } catch (Exception e) {
            logger.warn("🔍 DateSearchHelper: Failed to get field paths for {}: {}", paramName, e.getMessage());
        }
        
        // Fallback
        return List.of(paramName);
    }


    /**
     * Build a single date query for a specific field
     */
    private static SearchQuery buildDateQueryForField(String fieldPath, String start, String end, boolean inclusiveStart, boolean inclusiveEnd) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            return null;
        }

        // Check if this is a Period field
        if (fieldPath.endsWith("Period")) {
            return buildPeriodOverlapQuery(fieldPath, start, end, inclusiveStart, inclusiveEnd);
        } else {
            // Regular DateTime field
            logger.debug("🔍 DateSearchHelper: Using DateTime field: {}", fieldPath);
            DateRangeQuery query = SearchQuery.dateRange().field(fieldPath);
            if (start != null) query = query.start(start, inclusiveStart);
            if (end != null) query = query.end(end, inclusiveEnd);
            return query;
        }
    }
    
    /**
     * Build Period overlap query based on search criteria
     * Handles Period fields by creating appropriate overlap conditions using .start and .end
     */
    private static SearchQuery buildPeriodOverlapQuery(String periodField, String start, String end, boolean inclusiveStart, boolean inclusiveEnd) {
        logger.debug("🔍 DateSearchHelper: Building Period overlap query for: {}", periodField);
        
        String startField = periodField + ".start";
        String endField = periodField + ".end";
        
        if (start != null && end != null) {
            // Range query: find periods that overlap with [start, end]
            // Period overlaps if: period.start <= searchEnd AND period.end >= searchStart
            logger.debug("🔍 DateSearchHelper: Period range overlap: {} to {}", start, end);
            
            SearchQuery startOverlap = SearchQuery.dateRange().field(startField).end(end, inclusiveEnd);
            SearchQuery endOverlap = SearchQuery.dateRange().field(endField).start(start, inclusiveStart);
            
            return SearchQuery.conjuncts(startOverlap, endOverlap);
            
        } else if (start != null) {
            // Greater than query: find periods that start after the date
            // For gt/ge: period.start > searchDate (periods that start after the search date)
            logger.debug("🔍 DateSearchHelper: Period gt/ge query: periods starting after {}", start);
            return SearchQuery.dateRange().field(startField).start(start, inclusiveStart);
            
        } else if (end != null) {
            // Less than query: find periods that end before the date  
            // For lt/le: period.end < searchDate (periods that end before the search date)
            logger.debug("🔍 DateSearchHelper: Period lt/le query: periods ending before {}", end);
            return SearchQuery.dateRange().field(endField).end(end, inclusiveEnd);
            
        } else {
            // No search criteria - shouldn't happen, but return null
            logger.warn("🔍 DateSearchHelper: No search criteria provided for Period field: {}", periodField);
            return null;
        }
    }

    
    /**
     * Dynamically expand choice type fields to their concrete date-related variants using HAPI introspection
     * This replaces hardcoded heuristics with true FHIR metadata-driven expansion
     */
    public static List<String> expandDateChoicePaths(FhirContext fhirContext, String resourceType, String elementName) {
        try {
            ca.uhn.fhir.context.RuntimeResourceDefinition resDef = fhirContext.getResourceDefinition(resourceType);
            
            // Handle nested paths like "context.period"
            ca.uhn.fhir.context.BaseRuntimeChildDefinition child = null;
            if (elementName.contains(".")) {
                // Navigate nested path to find the actual child definition
                child = findNestedChild(resDef, elementName);
                logger.debug("🔍 DateSearchHelper: Nested child '{}' found: {}", elementName, (child != null));
            } else {
                // Simple field name
                child = resDef.getChildByName(elementName);
                if (child == null) {
                    child = resDef.getChildByName(elementName + "[x]");
                    logger.debug("🔍 DateSearchHelper: Child '{}[x]' found: {}", elementName, (child != null));
                }
            }
            
            if (child == null) {
                logger.debug("🔍 DateSearchHelper: Field '{}' not found in {}", elementName, resourceType);
                return List.of(elementName);
            }
            
            // Check for direct Period type first (more precise than field path navigation)
            if (child instanceof ca.uhn.fhir.context.RuntimeChildCompositeDatatypeDefinition
                && ((ca.uhn.fhir.context.RuntimeChildCompositeDatatypeDefinition) child).getDatatype()
                   == org.hl7.fhir.r4.model.Period.class) {
                logger.debug("🔍 DateSearchHelper: {} is a direct Period type, expanding to start/end", elementName);
                return List.of(elementName + ".start", elementName + ".end");
            }
            
            if (!(child instanceof ca.uhn.fhir.context.RuntimeChildChoiceDefinition)) {
                // Not a choice type - check if it's a Period field
                logger.debug("🔍 DateSearchHelper: {} is not a choice type, checking if it's a Period", elementName);

                if (isPeriodType(fhirContext, resourceType, elementName)) {
                    logger.debug("🔍 DateSearchHelper: {} is a Period type, expanding to start/end", elementName);
                    return List.of(elementName + ".start", elementName + ".end");
                }
                
                // Try fallback for choice types
                String suggestedField = FHIRPathParser.suggestDateTimeFieldPath(elementName);
                if (suggestedField != null && !suggestedField.equals(elementName)) {
                    logger.debug("🔍 DateSearchHelper: Using fallback suggestion: {} -> {}", elementName, suggestedField);
                    return List.of(suggestedField);
                }
                logger.debug("🔍 DateSearchHelper: Using element as-is: {}", elementName);
                return List.of(elementName);
            }

            ca.uhn.fhir.context.RuntimeChildChoiceDefinition choice = 
                (ca.uhn.fhir.context.RuntimeChildChoiceDefinition) child;
            List<String> paths = new ArrayList<>();

            logger.debug("🔍 DateSearchHelper: {} IS a choice type, processing variants", elementName);

            // Extract the nested path prefix (e.g., "target" from "target.dueDate")
            String pathPrefix = "";
            if (elementName.contains(".")) {
                int lastDotIndex = elementName.lastIndexOf(".");
                pathPrefix = elementName.substring(0, lastDotIndex + 1); // Include the dot
                logger.debug("🔍 DateSearchHelper: Nested path prefix: '{}'", pathPrefix);
            }
            
            for (Class<?> dataType : choice.getValidChildTypes()) {
                @SuppressWarnings("unchecked")
                String concreteName = choice.getChildNameByDatatype((Class<? extends org.hl7.fhir.instance.model.api.IBase>) dataType);
                
                // Preserve the nested path prefix
                String fullFieldName = pathPrefix + concreteName;

                logger.debug("🔍 DateSearchHelper: Found choice variant: {} -> {} (type: {})", elementName, fullFieldName, dataType.getSimpleName());

                // Handle date-like types for DATE search parameters
                if (dataType == org.hl7.fhir.r4.model.DateTimeType.class
                    // || dataType == org.hl7.fhir.r4.model.InstantType.class
                    ) {
                    paths.add(fullFieldName); // "effectiveDateTime" or "target.dueDateTime"
                    logger.debug("🔍 DateSearchHelper: Added DateTime/Instant variant: {}", fullFieldName);
                } else if (dataType == org.hl7.fhir.r4.model.DateType.class) {
                    paths.add(fullFieldName); // "dueDate" or "target.dueDate"
                    logger.debug("🔍 DateSearchHelper: Added Date variant: {}", fullFieldName);
                } else if (dataType == org.hl7.fhir.r4.model.Period.class) {
                    paths.add(fullFieldName + ".start"); // "effectivePeriod.start" or "target.duePeriod.start"
                    paths.add(fullFieldName + ".end");   // "effectivePeriod.end" or "target.duePeriod.end"
                    logger.debug("🔍 DateSearchHelper: Added Period variants: {}.start, {}.end", fullFieldName, fullFieldName);
                } else {
                    logger.debug("🔍 DateSearchHelper: Skipping non-date type: {} ({})", fullFieldName, dataType.getSimpleName());
                }
                // else if (dataType == org.hl7.fhir.r4.model.Timing.class) {
                //     paths.add(fullFieldName + ".event"); // "effectiveTiming.event" or "target.dueTiming.event"
                //     logger.info("🔍 DateSearchHelper: Added Timing variant: {}.event", fullFieldName);
                // }
                // Ignore other datatypes for DATE search
            }

            logger.debug("🔍 DateSearchHelper: Expanded {} to {} date-related paths: {}", elementName, paths.size(), paths);

            // If no date-related paths found, fallback to the element name itself
            if (paths.isEmpty()) {
                logger.warn("🔍 DateSearchHelper: No date-related paths found for choice type {}, using element as-is", elementName);
                return List.of(elementName);
            }
            
            return paths;
            
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Failed to expand choice type {}: {}", elementName, e.getMessage());
            // Fallback to original element name
            return List.of(elementName);
        }
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

    /**
     * Navigate nested field paths to find the actual child definition
     * For example: "context.period" -> navigate to context, then find period child
     */
    private static ca.uhn.fhir.context.BaseRuntimeChildDefinition findNestedChild(
            ca.uhn.fhir.context.RuntimeResourceDefinition resDef, String nestedPath) {
        try {
            String[] pathParts = nestedPath.split("\\.");
            ca.uhn.fhir.context.BaseRuntimeElementDefinition<?> currentDef = resDef;
            ca.uhn.fhir.context.BaseRuntimeChildDefinition lastChild = null;
            
            // Navigate through each part of the path except the last one
            for (int i = 0; i < pathParts.length; i++) {
                String part = pathParts[i];
                
                if (currentDef instanceof ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition) {
                    ca.uhn.fhir.context.BaseRuntimeChildDefinition childDef = 
                        ((ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition<?>) currentDef).getChildByName(part);
                    
                    if (childDef != null) {
                        lastChild = childDef;
                        if (i < pathParts.length - 1) {
                            // Not the last part, continue navigation
                            currentDef = childDef.getChildByName(part);
                        }
                    } else {
                        logger.debug("🔍 DateSearchHelper: Could not find nested field part '{}' in path '{}'", part, nestedPath);
                        return null;
                    }
                } else {
                    logger.debug("🔍 DateSearchHelper: Field part '{}' is not composite in nested path '{}'", part, nestedPath);
                    return null;
                }
            }
            
            return lastChild;
        } catch (Exception e) {
            logger.debug("🔍 DateSearchHelper: Failed to navigate nested path {}: {}", nestedPath, e.getMessage());
            return null;
        }
    }
}