package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FHIRTestSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(FHIRTestSearchService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private IParser jsonParser;
    
    // Default connection and bucket names
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";
    
    // Pagination constants
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    
    // FHIR prefix operators
    private static final Map<String, String> PREFIX_OPERATORS = Map.of(
        "eq", "=",      // equal (default)
        "ne", "!=",     // not equal 
        "gt", ">",      // greater than
        "lt", "<",      // less than
        "ge", ">=",     // greater or equal
        "le", "<=",     // less than or equal
        "sa", ">",      // starts after (dates)
        "eb", "<",      // ends before (dates)
        "ap", "~"       // approximately
    );
    
    // FHIR search parameter modifiers
    private static final Set<String> MODIFIERS = Set.of(
        "missing", "exact", "contains", "text", "not", "above", "below", "in", "not-in", "of-type"
    );
    
    // Fields that are CodeableConcept and need .coding.system/.coding.code instead of .system/.value
    private static final Set<String> CODEABLE_CONCEPT_FIELDS = Set.of(
        "clinicalStatus", "verificationStatus", "status", "category", "type", "class", "code",
        "criticality", "severity", "priority", "intent", "maritalStatus", "use"
    );
    
    // Fields that are simple codes (TOKEN type) but don't have .value/.system structure
    private static final Set<String> SIMPLE_CODE_FIELDS = Set.of(
        "gender", "active", "deceasedBoolean", "multipleBirthBoolean"
    );

    // FHIR search parameter field path resolution result
    private static class FieldPathMapping {
        final String fieldPath;
        final String systemField;
        final String systemValue;
        final boolean hasSystem;
        
        FieldPathMapping(String fieldPath, String systemField, String systemValue, boolean hasSystem) {
            this.fieldPath = fieldPath;
            this.systemField = systemField;
            this.systemValue = systemValue;
            this.hasSystem = hasSystem;
        }
    }
    
    @PostConstruct
    private void init() {
        logger.info("🚀 FHIRTestSearchService initialized with FHIR R4 context");
        
        // Configure parser for optimal performance
        jsonParser.setPrettyPrint(false);                    // ✅ No formatting
        jsonParser.setStripVersionsFromReferences(false);    // Keep references as-is
        jsonParser.setOmitResourceId(false);                 // Keep IDs
        jsonParser.setSummaryMode(false);                    // Full resources
        jsonParser.setOverrideResourceIdWithBundleEntryFullUrl(false); // Performance optimization
        
        // Disable validation during parsing for performance
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false);
        
        logger.info("✅ FHIR Search Service ready for FTS-based queries");
    }
    
    /**
     * Handle common US Core search parameters that are not in base FHIR R4
     */
    private FieldPathMapping handleUSCoreParameters(String paramName, String resourceType) {
        if ("Patient".equals(resourceType)) {
            switch (paramName) {
                case "language":
                    return new FieldPathMapping("communication.language.coding.code", 
                                               "communication.language.coding.system", null, true);
                case "marital-status":
                    return new FieldPathMapping("maritalStatus.coding.code", 
                                               "maritalStatus.coding.system", null, true);
                default:
                    return null;
            }
        }
        return null;
    }
    
    /**
     * Resolve field path from HAPI search parameter
     */
    private FieldPathMapping resolveFieldPathFromHapi(RuntimeSearchParam searchParam) {
        String hapiPath = searchParam.getPath();
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        logger.debug("Resolving field path - HAPI Path: {}, Type: {}", hapiPath, paramType);
        
        // Handle special case for full-text search
        if ("_text".equals(searchParam.getName())) {
            return new FieldPathMapping("", null, null, false);
        }
        
        // Convert Patient.xxx to xxx (remove resource type prefix)
        String basePath = convertToFieldPath(hapiPath);
        
        if (paramType == RestSearchParameterTypeEnum.TOKEN) {
            return handleTokenFieldPath(basePath, hapiPath, searchParam.getName());
        } else {
            return new FieldPathMapping(basePath, null, null, false);
        }
    }
    
    private String convertToFieldPath(String hapiPath) {
        return hapiPath.replaceFirst("^[^.]+\\.", "");
    }
    
    private FieldPathMapping handleTokenFieldPath(String basePath, String hapiPath, String paramName) {
        // Handle special filtered cases like "Patient.telecom.where(system='phone')"
        if (hapiPath.contains(".where(system=")) {
            return handleFilteredTokenPath(basePath, hapiPath);
        } else {
            // Check if this is a simple code field first
            if (SIMPLE_CODE_FIELDS.contains(basePath)) {
                logger.debug("Detected simple code field: {}", basePath);
                // Simple code fields don't have .value/.system structure - use field directly
                return new FieldPathMapping(basePath, null, null, false);
            } 
            // Check if this is a CodeableConcept field
            else if (CODEABLE_CONCEPT_FIELDS.contains(basePath)) {
                logger.debug("Detected CodeableConcept field: {}", basePath);
                String fieldPath = basePath + ".coding.code";
                String systemField = basePath + ".coding.system";
                return new FieldPathMapping(fieldPath, systemField, null, true);
            } else {
                logger.debug("Detected complex TOKEN field: {}", basePath);
                String fieldPath = basePath + ".value";
                String systemField = basePath + ".system";
                return new FieldPathMapping(fieldPath, systemField, null, true);
            }
        }
    }
    
    private FieldPathMapping handleFilteredTokenPath(String basePath, String hapiPath) {
        // Extract system value from "Patient.telecom.where(system='phone')"
        String systemValue = null;
        int whereIndex = hapiPath.indexOf(".where(system=");
        if (whereIndex != -1) {
            int startQuote = hapiPath.indexOf("'", whereIndex);
            int endQuote = hapiPath.indexOf("'", startQuote + 1);
            if (startQuote != -1 && endQuote != -1) {
                systemValue = hapiPath.substring(startQuote + 1, endQuote);
            }
        }
        
        String cleanBasePath = basePath.replaceFirst("\\.where\\(.*\\)", "");
        String fieldPath = cleanBasePath + ".value";
        String systemField = cleanBasePath + ".system";
        
        return new FieldPathMapping(fieldPath, systemField, systemValue, true);
    }
    
    /**
     * Main search method - always uses FTS with SEARCH function
     */
    public SearchResult searchResources(String resourceType, Map<String, String> queryParams, 
                                       String connectionName, String bucketName) {
        logger.info("🔍 FHIR Search request - Resource: {}, Parameters: {}", resourceType, queryParams);
        
        try {
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            
            // Check for _revinclude parameters
            List<String> revIncludeParams = extractRevIncludeParams(queryParams);
            boolean hasRevInclude = !revIncludeParams.isEmpty();
            
            if (hasRevInclude) {
                logger.info("🔗 _revinclude detected: {}", revIncludeParams);
                return executeRevIncludeSearch(resourceType, queryParams, cluster, connectionName, bucketName, revIncludeParams);
            } else {
                // Standard search
                String ftsQuery = buildFtsSearchQuery(resourceType, queryParams, bucketName, connectionName);
                logger.info("📡 Executing FTS query: {}", ftsQuery);
                
                QueryResult result = cluster.query(ftsQuery);
                List<Map<String, Object>> resources = processQueryResults(result);
                
                logger.info("📋 Search completed - Found {} {} resources", resources.size(), resourceType);
                return SearchResult.primaryOnly(resourceType, resources);
            }
                
        } catch (Exception e) {
            // Log clean error message without full stack trace
            logger.error("❌ Search failed for {} resources: {}", resourceType, e.getMessage());
            logger.debug("Full error details:", e); // Stack trace only at debug level
            throw new RuntimeException("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Build FTS search query using SEARCH function - never use straight N1QL
     */
    private String buildFtsSearchQuery(String resourceType, Map<String, String> queryParams, String bucketName, String connectionName) {
        // FTS index name: fully qualified <bucket>.Resources.<indexname>
        String ftsIndexName = bucketName + ".Resources.fts" + resourceType;
        
        // Build FTS query JSON
        JsonObject ftsQueryJson = buildFtsQueryJson(resourceType, queryParams, connectionName, bucketName);
        
        // Build the N1QL query using SEARCH function
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT resource.* FROM `")
                   .append(bucketName).append("`.`").append(DEFAULT_SCOPE).append("`.`")
                   .append(resourceType).append("` resource");
        
        queryBuilder.append(" WHERE SEARCH(resource, ")
                   .append(ftsQueryJson.toString())
                   .append(", {\"index\": \"").append(ftsIndexName).append("\"})");
        
        // Add simple N1QL condition to exclude deleted documents
        queryBuilder.append(" AND resource.deletedDate IS MISSING");
        
        return queryBuilder.toString();
    }
    
    /**
     * Build FTS query JSON from search parameters - now with pagination and chained query support
     */
    private JsonObject buildFtsQueryJson(String resourceType, Map<String, String> queryParams, String connectionName, String bucketName) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        List<String> ftsConditions = new ArrayList<>();
        
        // Extract pagination parameters
        PaginationParams pagination = extractPaginationParams(queryParams);
        
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String paramName = entry.getKey();
            String value = entry.getValue();
            
            // Skip control parameters (including pagination), but allow _id, _text, _lastUpdated, and _revinclude
            if (paramName.startsWith("_") && !paramName.equals("_text") && !paramName.equals("_id") && 
                !paramName.equals("_lastUpdated") && !paramName.equals("_revinclude")) {
                continue;
            }
            
            processSearchParameter(resourceDef, paramName, value, ftsConditions, connectionName, bucketName);
        }
        
        return buildFtsJsonQuery(ftsConditions, pagination);
    }
    
    /**
     * Process individual search parameter with chained query support
     */
    private void processSearchParameter(RuntimeResourceDefinition resourceDef, String paramName, 
                                      String value, List<String> ftsConditions, String connectionName, String bucketName) {
        
        ParsedParameter parsed = parseParameterName(paramName);
        
        // Handle special FHIR _id parameter
        if ("_id".equals(parsed.baseName)) {
            FieldPathMapping mapping = new FieldPathMapping("id", null, null, false);
            ParsedValue parsedValue = parseParameterValue(value, RestSearchParameterTypeEnum.TOKEN);
            generateFtsCondition(mapping, parsedValue, parsed, RestSearchParameterTypeEnum.TOKEN, ftsConditions);
            logger.debug("Processing _id parameter: {} -> id field", value);
            return;
        }
        
        // Handle special FHIR _lastUpdated parameter
        if ("_lastUpdated".equals(parsed.baseName)) {
            FieldPathMapping mapping = new FieldPathMapping("meta.lastUpdated", null, null, false);
            ParsedValue parsedValue = parseParameterValue(value, RestSearchParameterTypeEnum.DATE);
            generateFtsCondition(mapping, parsedValue, parsed, RestSearchParameterTypeEnum.DATE, ftsConditions);
            logger.debug("Processing _lastUpdated parameter: {} -> meta.lastUpdated field", value);
            return;
        }
        
        // Handle special composite parameters that need OR logic
        if (handleCompositeParameters(parsed.baseName, resourceDef.getName(), value, ftsConditions)) {
            return;
        }
        
        // Handle chained parameters (e.g., subject.name=Nicolas)
        if (handleChainedParameters(parsed.baseName, resourceDef.getName(), value, ftsConditions, connectionName, bucketName)) {
            return;
        }
        
        // Get HAPI parameter for dynamic field path resolution
        RuntimeSearchParam searchParam = resourceDef.getSearchParam(parsed.baseName);
        
        FieldPathMapping mapping;
        RestSearchParameterTypeEnum paramType;
        
        if (searchParam == null) {
            logger.info("📋 HAPI returned NULL for parameter: {} in resource: {}", parsed.baseName, resourceDef.getName());
            // Handle US Core parameters manually
            mapping = handleUSCoreParameters(parsed.baseName, resourceDef.getName());
            if (mapping == null) {
                logger.warn("Unknown search parameter: {} for resource type: {}", parsed.baseName, resourceDef.getName());
                return;
            }
            paramType = RestSearchParameterTypeEnum.TOKEN;
        } else {
            paramType = searchParam.getParamType();
            logger.info("📋 HAPI parameter info - Name: {}, Path: {}, Type: {}, Description: {}", 
                       searchParam.getName(), searchParam.getPath(), paramType, searchParam.getDescription());
            
            // Check for US Core override
            FieldPathMapping usCoreOverride = handleUSCoreParameters(parsed.baseName, resourceDef.getName());
            if (usCoreOverride != null) {
                logger.debug("Using US Core override for parameter: {}", parsed.baseName);
                mapping = usCoreOverride;
            } else {
                mapping = resolveFieldPathFromHapi(searchParam);
            }
        }
        
        logger.debug("Processing parameter: {} -> {}", parsed.baseName, mapping.fieldPath);
        
        // Parse value and prefixes
        ParsedValue parsedValue = parseParameterValue(value, paramType);
        
        // Generate FTS condition
        generateFtsCondition(mapping, parsedValue, parsed, paramType, ftsConditions);
    }
    
    /**
     * Generate FTS condition from mapping and parsed value
     */
    private void generateFtsCondition(FieldPathMapping mapping, ParsedValue parsedValue, 
                                    ParsedParameter parsed, RestSearchParameterTypeEnum paramType,
                                    List<String> ftsConditions) {
        
        String value = parsedValue.value;
        String prefix = parsedValue.prefix;
        
        // Handle special cases
        if (parsed.baseName.equals("_text")) {
            ftsConditions.add(value);
            return;
        }
        
        if (parsed.modifiers.contains("missing")) {
            // Handle missing modifier with N1QL condition
            logger.debug("Missing modifier not supported in FTS - parameter: {}", parsed.baseName);
            return;
        }
        
        if (parsed.modifiers.contains("not")) {
            prefix = "ne";
        }
        
        // Handle system|value format
        if (mapping.hasSystem && value.contains("|")) {
            handleSystemValueParameter(mapping, value, prefix, ftsConditions);
        } else if (mapping.hasSystem && mapping.systemValue != null) {
            handleImpliedSystemParameter(mapping, value, prefix, ftsConditions);
        } else {
            handleRegularParameter(mapping, value, prefix, parsed.modifiers, paramType, ftsConditions);
        }
    }
    
    private void handleSystemValueParameter(FieldPathMapping mapping, String value, String prefix, List<String> ftsConditions) {
        String[] parts = value.split("\\|", 2);
        String system = parts[0];
        String code = parts[1];
        
        if (!system.isEmpty() && !code.isEmpty()) {
            ftsConditions.add(mapping.systemField + ":match:" + system);
            ftsConditions.add(mapping.fieldPath + ":match:" + code);
        } else if (!code.isEmpty()) {
            ftsConditions.add(mapping.fieldPath + ":match:" + code);
        } else {
            ftsConditions.add(mapping.systemField + ":match:" + system);
        }
    }
    
    private void handleImpliedSystemParameter(FieldPathMapping mapping, String value, String prefix, List<String> ftsConditions) {
        ftsConditions.add(mapping.systemField + ":match:" + mapping.systemValue);
        ftsConditions.add(mapping.fieldPath + ":match:" + value);
    }
    
    private void handleRegularParameter(FieldPathMapping mapping, String value, String prefix, 
                                      Set<String> modifiers, RestSearchParameterTypeEnum paramType,
                                      List<String> ftsConditions) {
        
        String[] fieldPaths = mapping.fieldPath.split(",");
        String fieldPath = fieldPaths[0].trim(); // Use first field for multiple paths
        
        String condition = createSingleFieldCondition(fieldPath, value, prefix, modifiers, paramType);
        ftsConditions.add(condition);
    }
    
    private String createSingleFieldCondition(String fieldPath, String value, String prefix, 
                                            Set<String> modifiers, RestSearchParameterTypeEnum paramType) {
        if (modifiers.contains("exact")) {
            return fieldPath + ":match:" + value;
        } else if (modifiers.contains("contains")) {
            return fieldPath + ":wildcard:*" + value + "*";
        } else if (paramType == RestSearchParameterTypeEnum.DATE || paramType == RestSearchParameterTypeEnum.NUMBER) {
            return fieldPath + ":range:" + paramType.name() + ":" + prefix + ":" + value;
        } else if ("id".equals(fieldPath)) {
            // ID searches should be exact matches, not wildcard
            return fieldPath + ":match:" + value;
        } else if (SIMPLE_CODE_FIELDS.contains(fieldPath)) {
            // Simple code fields like gender should be exact matches, not wildcard
            return fieldPath + ":match:" + value;
        } else {
            // Preserve original case for FHIR compliance
            return fieldPath + ":wildcard:" + value + "*";
        }
    }
    
    /**
     * Process query results and extract resources
     */
    private List<Map<String, Object>> processQueryResults(QueryResult result) {
        List<Map<String, Object>> resources = new ArrayList<>();
        
        for (JsonObject row : result.rowsAs(JsonObject.class)) {
            Map<String, Object> rowMap = row.toMap();
            
            // Since we're using SELECT *, the resource data comes directly
            // The 'id' field should already be present in the resource
            resources.add(rowMap);
        }
        
        return resources;
    }
    
    /**
     * Parse parameter name and extract modifiers
     */
    private ParsedParameter parseParameterName(String paramName) {
        String[] parts = paramName.split(":");
        String baseName = parts[0];
        Set<String> modifiers = new HashSet<>();
        
        for (int i = 1; i < parts.length; i++) {
            if (MODIFIERS.contains(parts[i])) {
                modifiers.add(parts[i]);
            }
        }
        
        return new ParsedParameter(baseName, modifiers);
    }
    
    /**
     * Parse parameter value and extract prefix
     */
    private ParsedValue parseParameterValue(String value, RestSearchParameterTypeEnum paramType) {
        Pattern prefixPattern = Pattern.compile("^(eq|ne|gt|lt|ge|le|sa|eb|ap)(.+)");
        Matcher matcher = prefixPattern.matcher(value);
        
        String prefix = "eq"; // default
        String actualValue = value;
        
        if (matcher.matches()) {
            prefix = matcher.group(1);
            actualValue = matcher.group(2);
        }
        
        return new ParsedValue(prefix, actualValue, paramType);
    }
    
    /**
     * Build FTS JSON query from conditions with pagination
     */
    private JsonObject buildFtsJsonQuery(List<String> ftsConditions, PaginationParams pagination) {
        JsonObject query = JsonObject.create();
        JsonObject mainQuery;
        
        // Separate regular conditions from special conditions
        List<String> regularConditions = new ArrayList<>();
        List<String> compositeConditions = new ArrayList<>();
        List<String> chainedConditions = new ArrayList<>();
        boolean hasNoResultsCondition = false;
        
        for (String condition : ftsConditions) {
            if (condition.startsWith("_composite_")) {
                compositeConditions.add(condition);
            } else if (condition.startsWith("_chained_")) {
                if (condition.equals("_chained_no_results:true")) {
                    hasNoResultsCondition = true;
                } else {
                    chainedConditions.add(condition);
                }
            } else {
                regularConditions.add(condition);
            }
        }
        
        // Handle impossible condition first
        if (hasNoResultsCondition) {
            // Create impossible condition that matches nothing
            JsonObject impossibleQuery = JsonObject.create();
            impossibleQuery.put("match", "___IMPOSSIBLE_MATCH___");
            impossibleQuery.put("field", "___NON_EXISTENT_FIELD___");
            mainQuery = impossibleQuery;
        } else if (regularConditions.isEmpty() && compositeConditions.isEmpty() && chainedConditions.isEmpty()) {
            // Match all documents
            mainQuery = JsonObject.create().put("match_all", JsonObject.create());
        } else {
            // Build query with all condition types
            JsonArray conjuncts = JsonArray.create();
            
            // Add regular conditions
            for (String conditionStr : regularConditions) {
                FtsCondition condition = parseFtsCondition(conditionStr);
                conjuncts.add(createMatchQuery(condition));
            }
            
            // Add composite conditions
            for (String compositeCondition : compositeConditions) {
                conjuncts.add(handleCompositeCondition(compositeCondition));
            }
            
            // Add chained conditions
            for (String chainedCondition : chainedConditions) {
                conjuncts.add(handleChainedCondition(chainedCondition));
            }
            
            if (conjuncts.size() == 1) {
                mainQuery = (JsonObject) conjuncts.get(0);
            } else {
                mainQuery = JsonObject.create().put("conjuncts", conjuncts);
            }
        }
        
        query.put("query", mainQuery);
        
        // Add pagination parameters
        query.put("from", pagination.offset);
        query.put("size", pagination.size);
        
        logger.debug("FTS pagination: offset={}, size={}", pagination.offset, pagination.size);
        
        return query;
    }
    
    private JsonObject createMatchQuery(FtsCondition condition) {
        JsonObject matchQuery = JsonObject.create();
        
        if (condition.field.isEmpty()) {
            matchQuery.put("match", condition.value);
            return matchQuery;
        }
        
        String[] parts = condition.value.split(":", 2);
        if (parts.length != 2) {
            matchQuery.put("match", condition.value);
            matchQuery.put("field", condition.field);
            return matchQuery;
        }
        
        String type = parts[0];
        String value = parts[1];
        
        switch (type) {
            case "match":
                matchQuery.put("match", value);
                matchQuery.put("field", condition.field);
                break;
            case "wildcard":
                matchQuery.put("wildcard", value);
                matchQuery.put("field", condition.field);
                break;
            case "range":
                handleRangeQuery(matchQuery, condition.field, value);
                break;
            default:
                matchQuery.put("match", value);
                matchQuery.put("field", condition.field);
        }
        
        return matchQuery;
    }
    
    private void handleRangeQuery(JsonObject matchQuery, String field, String value) {
        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            matchQuery.put("match", value);
            matchQuery.put("field", field);
            return;
        }
        
        String paramType = parts[0];
        String prefix = parts[1];
        String actualValue = parts[2];
        
        matchQuery.put("field", field);
        
        if ("DATE".equals(paramType)) {
            handleDateRangeQuery(matchQuery, prefix, actualValue);
        } else {
            handleNumberRangeQuery(matchQuery, prefix, actualValue);
        }
    }
    
    private void handleDateRangeQuery(JsonObject matchQuery, String prefix, String dateValue) {
        switch (prefix) {
            case "eq":
                matchQuery.put("start", dateValue);
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_start", true);
                matchQuery.put("inclusive_end", true);
                break;
            case "ge": 
                matchQuery.put("start", dateValue);
                matchQuery.put("inclusive_start", true);
                break;
            case "gt":
            case "sa":
                matchQuery.put("start", dateValue);
                matchQuery.put("inclusive_start", false);
                break;
            case "le":
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_end", true);
                break;
            case "lt":
            case "eb":
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_end", false);
                break;
            default:
                matchQuery.put("match", dateValue);
        }
    }
    
    private void handleNumberRangeQuery(JsonObject matchQuery, String prefix, String numberValue) {
        switch (prefix) {
            case "lt":
                matchQuery.put("max", numberValue);
                matchQuery.put("inclusive_max", false);
                break;
            case "le":
                matchQuery.put("max", numberValue);
                matchQuery.put("inclusive_max", true);
                break;
            case "gt":
                matchQuery.put("min", numberValue);
                matchQuery.put("inclusive_min", false);
                break;
            case "ge":
                matchQuery.put("min", numberValue);
                matchQuery.put("inclusive_min", true);
                break;
            case "eq":
            default:
                matchQuery.put("match", numberValue);
        }
    }
    
    /**
     * Handle chained conditions that match against referenced resource IDs
     */
    private JsonObject handleChainedCondition(String chainedCondition) {
        // Parse chained condition: "_chained_subject:Patient/id1,Patient/id2"
        String[] parts = chainedCondition.split(":", 2);
        if (parts.length != 2) {
            logger.warn("Invalid chained condition format: {}", chainedCondition);
            return JsonObject.create().put("match_all", JsonObject.create());
        }
        
        String referenceField = parts[0].substring("_chained_".length()); // Remove "_chained_" prefix
        String[] resourceIds = parts[1].split(",");
        
        logger.info("🔗 Creating disjunct query for {} with {} resource IDs", referenceField, resourceIds.length);
        
        // Create disjuncts (OR) for all referenced resource IDs using exact matching
        JsonArray disjuncts = JsonArray.create();
        
        for (String resourceId : resourceIds) {
            JsonObject referenceMatch = JsonObject.create();
            // Use exact match for references - much faster than wildcard (62ms → 13ms!)
            referenceMatch.put("match", resourceId);
            referenceMatch.put("field", referenceField + ".reference");
            disjuncts.add(referenceMatch);
        }
        
        return JsonObject.create().put("disjuncts", disjuncts);
    }
    
    /**
     * Handle composite conditions that require OR logic
     */
    private JsonObject handleCompositeCondition(String compositeCondition) {
        if (compositeCondition.startsWith("_composite_name_or:")) {
            String value = compositeCondition.substring("_composite_name_or:".length());
            logger.info("🔍 Creating OR query for name fields with value: {}", value);
            
            // Create disjuncts (OR) for Patient name fields
            JsonArray disjuncts = JsonArray.create();
            
            // Search in name.family
            JsonObject familyMatch = JsonObject.create();
            familyMatch.put("wildcard", value + "*");
            familyMatch.put("field", "name.family");
            disjuncts.add(familyMatch);
            
            // Search in name.given
            JsonObject givenMatch = JsonObject.create();
            givenMatch.put("wildcard", value + "*");
            givenMatch.put("field", "name.given");
            disjuncts.add(givenMatch);
            
            // Search in name.prefix
            JsonObject prefixMatch = JsonObject.create();
            prefixMatch.put("wildcard", value + "*");
            prefixMatch.put("field", "name.prefix");
            disjuncts.add(prefixMatch);
            
            // Search in name.suffix
            JsonObject suffixMatch = JsonObject.create();
            suffixMatch.put("wildcard", value + "*");
            suffixMatch.put("field", "name.suffix");
            disjuncts.add(suffixMatch);
            
            return JsonObject.create().put("disjuncts", disjuncts);
        }
        
        // Handle other composite conditions as needed
        logger.warn("Unknown composite condition: {}", compositeCondition);
        return JsonObject.create().put("match_all", JsonObject.create());
    }
    
    private FtsCondition parseFtsCondition(String condition) {
        if (!condition.contains(":")) {
            return new FtsCondition("", condition);
        }
        
        String[] parts = condition.split(":", 3);
        if (parts.length != 3) {
            return new FtsCondition("", condition);
        }
        
        String field = parts[0];
        String type = parts[1];
        String value = parts[2];
        
        return new FtsCondition(field, type + ":" + value);
    }
    
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }

    /**
     * Create a proper FHIR Bundle response using HAPI FHIR utilities - supports _revinclude
     */
    public Bundle createSearchBundle(SearchResult searchResult, String baseUrl, Map<String, String> searchParams) {
        String resourceType = searchResult.getPrimaryResourceType();
        List<Map<String, Object>> primaryResources = searchResult.getPrimaryResources();
        Map<String, List<Map<String, Object>>> includedResources = searchResult.getIncludedResources();
        
        logger.info("📦 Creating FHIR Bundle - Primary: {} {}, Included: {} resource types", 
                   primaryResources.size(), resourceType, includedResources.size());
        
        try {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            bundle.setId(UUID.randomUUID().toString());
            
            // Total includes both primary and included resources
            int totalResources = primaryResources.size();
            for (List<Map<String, Object>> included : includedResources.values()) {
                totalResources += included.size();
            }
            bundle.setTotal(totalResources);
            bundle.setTimestamp(new Date());
            
            // Minimal Bundle meta for performance
            Meta bundleMeta = new Meta();
            bundleMeta.setLastUpdated(new Date());
            bundle.setMeta(bundleMeta);
            
            // Add self link
            Bundle.BundleLinkComponent selfLink = new Bundle.BundleLinkComponent();
            selfLink.setRelation("self");
            selfLink.setUrl(buildSearchUrl(baseUrl, resourceType, searchParams));
            bundle.addLink(selfLink);
            
            // Pre-allocate entry list for better performance
            List<Bundle.BundleEntryComponent> entries = new ArrayList<>(totalResources);
            
            // Reuse ObjectMapper for better performance
            ObjectMapper objectMapper = new ObjectMapper();
            
            // Add primary resources first (search mode: match)
            for (Map<String, Object> rawResource : primaryResources) {
                try {
                    Bundle.BundleEntryComponent entry = createBundleEntry(rawResource, resourceType, baseUrl, 
                                                                         objectMapper, Bundle.SearchEntryMode.MATCH);
                    entries.add(entry);
                } catch (Exception e) {
                    logger.error("Failed to process primary resource {}: {}", rawResource.get("id"), e.getMessage());
                    logger.debug("Primary resource processing error details:", e);
                    throw new RuntimeException("Primary resource processing failed: " + e.getMessage());
                }
            }
            
            // Add included resources (search mode: include)
            for (Map.Entry<String, List<Map<String, Object>>> includeEntry : includedResources.entrySet()) {
                String includeResourceType = includeEntry.getKey();
                List<Map<String, Object>> includeList = includeEntry.getValue();
                
                for (Map<String, Object> rawResource : includeList) {
                    try {
                        Bundle.BundleEntryComponent entry = createBundleEntry(rawResource, includeResourceType, baseUrl,
                                                                             objectMapper, Bundle.SearchEntryMode.INCLUDE);
                        entries.add(entry);
                    } catch (Exception e) {
                        logger.error("Failed to process included resource {}: {}", rawResource.get("id"), e.getMessage());
                        logger.debug("Included resource processing error details:", e);
                        // Continue processing other resources instead of failing completely
                    }
                }
                
                logger.info("✅ Added {} {} resources as included", includeList.size(), includeResourceType);
            }
            
            // Set all entries at once for better performance
            bundle.setEntry(entries);
            
            logger.info("✅ Created Bundle with {} total entries ({} primary, {} included)", 
                       entries.size(), primaryResources.size(), entries.size() - primaryResources.size());
            return bundle;
            
        } catch (Exception e) {
            logger.error("❌ Failed to create FHIR Bundle: {}", e.getMessage());
            logger.debug("Bundle creation error details:", e);
            throw new RuntimeException("Failed to create FHIR Bundle: " + e.getMessage());
        }
    }
    
    /**
     * Create a single bundle entry from a raw resource map
     */
    private Bundle.BundleEntryComponent createBundleEntry(Map<String, Object> rawResource, String resourceType, 
                                                         String baseUrl, ObjectMapper objectMapper, 
                                                         Bundle.SearchEntryMode searchMode) throws Exception {
        // Fast JSON conversion
        String resourceJson = objectMapper.writeValueAsString(rawResource);
        IBaseResource fhirResource = jsonParser.parseResource(resourceJson);
        
        // Create entry with minimal metadata
        Bundle.BundleEntryComponent entry = new Bundle.BundleEntryComponent();
        entry.setResource((Resource) fhirResource);
        
        // Set fullUrl (required for search bundles)
        String resourceId = rawResource.get("id").toString();
        entry.setFullUrl(baseUrl + "/" + resourceType + "/" + resourceId);
        
        // Set search metadata
        Bundle.BundleEntrySearchComponent search = new Bundle.BundleEntrySearchComponent();
        search.setMode(searchMode);
        entry.setSearch(search);
        
        return entry;
    }
    
    /**
     * Build search URL for self link
     */
    private String buildSearchUrl(String baseUrl, String resourceType, Map<String, String> searchParams) {
        StringBuilder url = new StringBuilder(baseUrl).append("/").append(resourceType);
        
        if (searchParams != null && !searchParams.isEmpty()) {
            url.append("?");
            searchParams.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("connectionName") && !entry.getKey().equals("bucketName"))
                .forEach(entry -> url.append(entry.getKey()).append("=").append(entry.getValue()).append("&"));
            
            if (url.charAt(url.length() - 1) == '&') {
                url.deleteCharAt(url.length() - 1);
            }
        }
        
        return url.toString();
    }
    
    /**
     * Convert a Bundle to JSON string
     */
    public String getBundleAsJson(Bundle bundle) {
        logger.debug("Serializing Bundle to JSON");
        return jsonParser.encodeResourceToString(bundle);
    }
    
    /**
     * Handle special composite parameters that require OR logic instead of AND
     */
    private boolean handleCompositeParameters(String paramName, String resourceType, String value, List<String> ftsConditions) {
        if ("Patient".equals(resourceType) && "name".equals(paramName)) {
            logger.info("🔍 Handling composite 'name' search for Patient: {}", value);
            
            // Create OR condition for name fields (family, given, prefix, suffix)
            // This will be handled specially in buildFtsJsonQuery
            String nameCondition = "_composite_name_or:" + value;
            ftsConditions.add(nameCondition);
            return true;
        }
        
        // Add more composite parameters as needed
        // if ("Organization".equals(resourceType) && "name".equals(paramName)) { ... }
        
        return false;
    }
    
    /**
     * Handle chained parameters like subject.name=Nicolas
     * Step 1: Query referenced resource type to get IDs
     * Step 2: Create disjunct condition for main resource
     */
    private boolean handleChainedParameters(String paramName, String resourceType, String value, 
                                          List<String> ftsConditions, String connectionName, String bucketName) {
        
        // Check if this is a chained parameter (contains a dot)
        if (!paramName.contains(".")) {
            return false;
        }
        
        logger.info("🔗 Handling chained parameter: {} = {}", paramName, value);
        
        try {
            // Parse the chain: "subject.name" -> referenceField="subject", chainedParam="name"
            String[] parts = paramName.split("\\.", 2);
            String referenceField = parts[0];
            String chainedParam = parts[1];
            
            // Determine referenced resource type from reference field
            String referencedResourceType = determineReferencedResourceType(resourceType, referenceField);
            if (referencedResourceType == null) {
                logger.warn("Could not determine referenced resource type for {}.{}", resourceType, referenceField);
                return false;
            }
            
            logger.info("🔗 Chain resolved: {} -> {} ({}={})", referenceField, referencedResourceType, chainedParam, value);
            
            // Step 1: Execute chained query to get referenced resource IDs
            List<String> referencedResourceIds = executeChainedQuery(referencedResourceType, chainedParam, value, connectionName, bucketName);
            
            if (referencedResourceIds.isEmpty()) {
                logger.info("🔗 No matching {} resources found for {}={}, adding impossible condition", referencedResourceType, chainedParam, value);
                // Add impossible condition to ensure no results
                ftsConditions.add("_chained_no_results:true");
                return true;
            }
            
            logger.info("🔗 Found {} matching {} resources", referencedResourceIds.size(), referencedResourceType);
            
            // Step 2: Create chained condition for main resource
            String chainedCondition = "_chained_" + referenceField + ":" + String.join(",", referencedResourceIds);
            ftsConditions.add(chainedCondition);
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process chained parameter {}: {}", paramName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Determine the referenced resource type based on the main resource type and reference field
     */
    private String determineReferencedResourceType(String resourceType, String referenceField) {
        // Map common reference fields to their target resource types
        Map<String, String> commonReferences = Map.of(
            "subject", "Patient",           // Observation.subject, DiagnosticReport.subject, etc.
            "patient", "Patient",           // Encounter.patient, Condition.patient, etc.
            "performer", "Practitioner",    // Observation.performer
            "encounter", "Encounter",       // Observation.encounter, Condition.encounter
            "organization", "Organization", // Patient.organization, Practitioner.organization
            "location", "Location"          // Encounter.location
        );
        
        return commonReferences.get(referenceField.toLowerCase());
    }
    
    /**
     * Execute chained query to get IDs of referenced resources
     */
    private List<String> executeChainedQuery(String referencedResourceType, String chainedParam, String value, String connectionName, String bucketName) {
        try {
            logger.info("🔗 Executing chained query: {} where {}={}", referencedResourceType, chainedParam, value);
            
            // Create temporary search params for the referenced resource
            Map<String, String> chainedSearchParams = new HashMap<>();
            chainedSearchParams.put(chainedParam, value);
            
            // Build FTS query for referenced resource (without chained parameters to avoid recursion)
            RuntimeResourceDefinition referencedResourceDef = fhirContext.getResourceDefinition(referencedResourceType);
            List<String> chainedFtsConditions = new ArrayList<>();
            
            // Process the chained parameter (but without chained parameter handling to avoid recursion)
            processChainedSearchParameter(referencedResourceDef, chainedParam, value, chainedFtsConditions);
            
            // Build simple FTS query to get resource IDs
            String ftsIndexName = bucketName + ".Resources.fts" + referencedResourceType;
            JsonObject ftsQueryJson = buildSimpleFtsQuery(chainedFtsConditions);
            
            // Execute query to get META().id only for performance
            Cluster cluster = connectionService.getConnection(connectionName);
            String chainedQuery = String.format(
                "SELECT RAW META(resource).id FROM `%s`.`%s`.`%s` resource " +
                "WHERE SEARCH(resource, %s, {\"index\": \"%s\"}) " +
                "AND resource.deletedDate IS MISSING",
                bucketName, DEFAULT_SCOPE, referencedResourceType, 
                ftsQueryJson.toString(), ftsIndexName
            );
            
            logger.info("🔗 Chained query: {}", chainedQuery);
            
            QueryResult result = cluster.query(chainedQuery);
            List<String> resourceIds = new ArrayList<>();
            
            // RAW query returns strings directly, not JsonObjects
            for (String documentKey : result.rowsAs(String.class)) {
                // documentKey is already in "ResourceType/id" format from META().id
                resourceIds.add(documentKey);
            }
            
            logger.info("🔗 Chained query returned {} resource IDs", resourceIds.size());
            return resourceIds;
            
        } catch (Exception e) {
            logger.error("Failed to execute chained query for {}: {}", referencedResourceType, e.getMessage());
            return new ArrayList<>(); // Return empty list on error
        }
    }
    
    /**
     * Process search parameter without chained parameter handling (to avoid recursion)
     */
    private void processChainedSearchParameter(RuntimeResourceDefinition resourceDef, String paramName, 
                                             String value, List<String> ftsConditions) {
        
        ParsedParameter parsed = parseParameterName(paramName);
        
        // Handle special FHIR _id parameter
        if ("_id".equals(parsed.baseName)) {
            FieldPathMapping mapping = new FieldPathMapping("id", null, null, false);
            ParsedValue parsedValue = parseParameterValue(value, RestSearchParameterTypeEnum.TOKEN);
            generateFtsCondition(mapping, parsedValue, parsed, RestSearchParameterTypeEnum.TOKEN, ftsConditions);
            return;
        }
        
        // Handle special composite parameters
        if (handleCompositeParameters(parsed.baseName, resourceDef.getName(), value, ftsConditions)) {
            return;
        }
        
        // Regular parameter processing (without chained handling)
        RuntimeSearchParam searchParam = resourceDef.getSearchParam(parsed.baseName);
        
        FieldPathMapping mapping;
        RestSearchParameterTypeEnum paramType;
        
        if (searchParam == null) {
            mapping = handleUSCoreParameters(parsed.baseName, resourceDef.getName());
            if (mapping == null) {
                logger.warn("Unknown search parameter: {} for resource type: {}", parsed.baseName, resourceDef.getName());
                return;
            }
            paramType = RestSearchParameterTypeEnum.TOKEN;
        } else {
            paramType = searchParam.getParamType();
            mapping = resolveFieldPathFromHapi(searchParam);
        }
        
        ParsedValue parsedValue = parseParameterValue(value, paramType);
        generateFtsCondition(mapping, parsedValue, parsed, paramType, ftsConditions);
    }
    
    /**
     * Build simple FTS query without pagination for chained queries
     */
    private JsonObject buildSimpleFtsQuery(List<String> ftsConditions) {
        JsonObject query = JsonObject.create();
        JsonObject mainQuery;
        
        // Separate regular and composite conditions
        List<String> regularConditions = new ArrayList<>();
        List<String> compositeConditions = new ArrayList<>();
        
        for (String condition : ftsConditions) {
            if (condition.startsWith("_composite_")) {
                compositeConditions.add(condition);
            } else {
                regularConditions.add(condition);
            }
        }
        
        if (regularConditions.isEmpty() && compositeConditions.isEmpty()) {
            mainQuery = JsonObject.create().put("match_all", JsonObject.create());
        } else if (regularConditions.size() == 1 && compositeConditions.isEmpty()) {
            // Single regular condition
            FtsCondition condition = parseFtsCondition(regularConditions.get(0));
            mainQuery = createMatchQuery(condition);
        } else if (regularConditions.isEmpty() && compositeConditions.size() == 1) {
            // Single composite condition
            mainQuery = handleCompositeCondition(compositeConditions.get(0));
        } else {
            // Multiple conditions
            JsonArray conjuncts = JsonArray.create();
            
            // Add regular conditions
            for (String conditionStr : regularConditions) {
                FtsCondition condition = parseFtsCondition(conditionStr);
                conjuncts.add(createMatchQuery(condition));
            }
            
            // Add composite conditions
            for (String compositeCondition : compositeConditions) {
                conjuncts.add(handleCompositeCondition(compositeCondition));
            }
            
            if (conjuncts.size() == 1) {
                mainQuery = (JsonObject) conjuncts.get(0);
            } else {
                mainQuery = JsonObject.create().put("conjuncts", conjuncts);
            }
        }
        
        query.put("query", mainQuery);
        query.put("size", 1000); // Always use large size for chained queries to get all referenced IDs
        query.put("from", 0);    // Always start from beginning for chained queries
        
        return query;
    }
    
    /**
     * Extract pagination parameters from query params
     */
    private PaginationParams extractPaginationParams(Map<String, String> queryParams) {
        int offset = 0;
        int size = DEFAULT_PAGE_SIZE;
        
        // Check for _offset parameter
        if (queryParams.containsKey("_offset")) {
            try {
                offset = Math.max(0, Integer.parseInt(queryParams.get("_offset")));
            } catch (NumberFormatException e) {
                logger.warn("Invalid _offset parameter: {}, using default: 0", queryParams.get("_offset"));
            }
        }
        
        // Check for _count parameter (FHIR standard)
        if (queryParams.containsKey("_count")) {
            try {
                size = Integer.parseInt(queryParams.get("_count"));
                size = Math.min(Math.max(1, size), MAX_PAGE_SIZE); // Clamp between 1 and MAX_PAGE_SIZE
            } catch (NumberFormatException e) {
                logger.warn("Invalid _count parameter: {}, using default: {}", queryParams.get("_count"), DEFAULT_PAGE_SIZE);
            }
        }
        
        return new PaginationParams(offset, size);
    }
    

    
    // Helper classes
    private static class ParsedParameter {
        final String baseName;
        final Set<String> modifiers;
        
        ParsedParameter(String baseName, Set<String> modifiers) {
            this.baseName = baseName;
            this.modifiers = modifiers;
        }
    }
    
    private static class ParsedValue {
        final String prefix;
        final String value;
        final RestSearchParameterTypeEnum paramType;
        
        ParsedValue(String prefix, String value, RestSearchParameterTypeEnum paramType) {
            this.prefix = prefix;
            this.value = value;
            this.paramType = paramType;
        }
    }
    
    private static class FtsCondition {
        final String field;
        final String value;
        
        FtsCondition(String field, String value) {
            this.field = field;
            this.value = value;
        }
    }
    
    // Helper class for pagination parameters
    private static class PaginationParams {
        final int offset;
        final int size;
        
        PaginationParams(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }
    
    // Helper class for search results that may include primary and included resources
    public static class SearchResult {
        private final String primaryResourceType;
        private final List<Map<String, Object>> primaryResources;
        private final Map<String, List<Map<String, Object>>> includedResources;
        private final boolean hasIncludes;
        
        private SearchResult(String primaryResourceType, List<Map<String, Object>> primaryResources, 
                           Map<String, List<Map<String, Object>>> includedResources, boolean hasIncludes) {
            this.primaryResourceType = primaryResourceType;
            this.primaryResources = primaryResources;
            this.includedResources = includedResources;
            this.hasIncludes = hasIncludes;
        }
        
        public static SearchResult primaryOnly(String resourceType, List<Map<String, Object>> resources) {
            return new SearchResult(resourceType, resources, new HashMap<>(), false);
        }
        
        public static SearchResult withIncludes(String primaryResourceType, List<Map<String, Object>> primaryResources,
                                              Map<String, List<Map<String, Object>>> includedResources) {
            return new SearchResult(primaryResourceType, primaryResources, includedResources, true);
        }
        
        public String getPrimaryResourceType() { return primaryResourceType; }
        public List<Map<String, Object>> getPrimaryResources() { return primaryResources; }
        public Map<String, List<Map<String, Object>>> getIncludedResources() { return includedResources; }
        public boolean hasIncludes() { return hasIncludes; }
        
        public List<Map<String, Object>> getAllResources() {
            List<Map<String, Object>> allResources = new ArrayList<>(primaryResources);
            includedResources.values().forEach(allResources::addAll);
            return allResources;
        }
    }
    
    /**
     * Extract _revinclude parameters from query params
     */
    private List<String> extractRevIncludeParams(Map<String, String> queryParams) {
        List<String> revIncludes = new ArrayList<>();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if ("_revinclude".equals(entry.getKey())) {
                revIncludes.add(entry.getValue());
            }
        }
        return revIncludes;
    }
    
    /**
     * Execute search with _revinclude support
     */
    private SearchResult executeRevIncludeSearch(String resourceType, Map<String, String> queryParams,
                                               Cluster cluster, String connectionName, String bucketName,
                                               List<String> revIncludeParams) {
        try {
            logger.info("🔗 Executing _revinclude search for {}", resourceType);
            
            // Step 1: Get primary resources with both ID and full document
            SearchResultWithIds primaryResult = getPrimaryResourcesWithIds(resourceType, queryParams, cluster, connectionName, bucketName);
            List<Map<String, Object>> primaryResources = primaryResult.resources;
            List<String> primaryResourceIds = primaryResult.resourceIds;
            
            logger.info("📋 Found {} primary {} resources", primaryResources.size(), resourceType);
            
            if (primaryResourceIds.isEmpty()) {
                // No primary resources found, return empty result
                return SearchResult.primaryOnly(resourceType, primaryResources);
            }
            
            // Step 2: Process each _revinclude parameter
            Map<String, List<Map<String, Object>>> includedResources = new HashMap<>();
            
            for (String revIncludeParam : revIncludeParams) {
                processRevIncludeParameter(revIncludeParam, primaryResourceIds, cluster, connectionName, bucketName, includedResources);
            }
            
            logger.info("📋 _revinclude search completed - Primary: {} {}, Included: {} resource types", 
                       primaryResources.size(), resourceType, includedResources.size());
            
            return SearchResult.withIncludes(resourceType, primaryResources, includedResources);
            
        } catch (Exception e) {
            logger.error("❌ _revinclude search failed: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Get primary resources with both full documents and resource IDs
     */
    private SearchResultWithIds getPrimaryResourcesWithIds(String resourceType, Map<String, String> queryParams,
                                                          Cluster cluster, String connectionName, String bucketName) {
        // Remove _revinclude and pagination params for primary search (need all primary resources for _revinclude)
        Map<String, String> primaryQueryParams = new HashMap<>(queryParams);
        primaryQueryParams.remove("_revinclude");
        primaryQueryParams.remove("_count");  // Remove pagination for _revinclude
        primaryQueryParams.remove("_offset"); // Remove pagination for _revinclude
        
        // Build query that gets both ID and full document - with forced size/from for _revinclude
        String ftsIndexName = bucketName + ".Resources.fts" + resourceType;
        JsonObject ftsQueryJson = buildFtsQueryJson(resourceType, primaryQueryParams, connectionName, bucketName);
        
        // Override pagination settings for _revinclude - we need all primary resources
        ftsQueryJson.put("size", 1000); // Always use large size for _revinclude primary query
        ftsQueryJson.put("from", 0);    // Always start from beginning for _revinclude primary query
        
        String primaryQuery = String.format(
            "SELECT META(resource).id as documentKey, resource.* FROM `%s`.`%s`.`%s` resource " +
            "WHERE SEARCH(resource, %s, {\"index\": \"%s\"}) " +
            "AND resource.deletedDate IS MISSING",
            bucketName, DEFAULT_SCOPE, resourceType, 
            ftsQueryJson.toString(), ftsIndexName
        );
        
        logger.info("📡 Primary query with IDs: {}", primaryQuery);
        
        QueryResult result = cluster.query(primaryQuery);
        List<Map<String, Object>> resources = new ArrayList<>();
        List<String> resourceIds = new ArrayList<>();
        
        for (JsonObject row : result.rowsAs(JsonObject.class)) {
            Map<String, Object> rowMap = row.toMap();
            
            // Extract document key (resource ID)
            String documentKey = (String) rowMap.get("documentKey");
            if (documentKey != null) {
                resourceIds.add(documentKey);
            }
            
            // Remove documentKey from resource data
            rowMap.remove("documentKey");
            
            resources.add(rowMap);
        }
        
        return new SearchResultWithIds(resources, resourceIds);
    }
    
    /**
     * Process a single _revinclude parameter
     */
    private void processRevIncludeParameter(String revIncludeParam, List<String> primaryResourceIds,
                                          Cluster cluster, String connectionName, String bucketName,
                                          Map<String, List<Map<String, Object>>> includedResources) {
        try {
            // Parse _revinclude parameter: "Observation:subject"
            String[] parts = revIncludeParam.split(":", 2);
            if (parts.length != 2) {
                logger.warn("Invalid _revinclude parameter format: {}", revIncludeParam);
                return;
            }
            
            String includedResourceType = parts[0];
            String referenceField = parts[1];
            
            logger.info("🔗 Processing _revinclude: {} -> {} via {}", includedResourceType, referenceField, primaryResourceIds.size() + " primary resources");
            
            // Create disjunct query for all primary resource IDs
            JsonArray disjuncts = JsonArray.create();
            for (String resourceId : primaryResourceIds) {
                JsonObject referenceMatch = JsonObject.create();
                referenceMatch.put("match", resourceId);
                referenceMatch.put("field", referenceField + ".reference");
                disjuncts.add(referenceMatch);
            }
            
            // Build FTS query for included resources
            JsonObject includeQuery = JsonObject.create();
            includeQuery.put("query", JsonObject.create().put("disjuncts", disjuncts));
            includeQuery.put("size", 1000); // Always use large size for _revinclude to get all matching resources
            includeQuery.put("from", 0);    // Always start from beginning for _revinclude queries
            
            String ftsIndexName = bucketName + ".Resources.fts" + includedResourceType;
            String includeQuerySql = String.format(
                "SELECT resource.* FROM `%s`.`%s`.`%s` resource " +
                "WHERE SEARCH(resource, %s, {\"index\": \"%s\"}) " +
                "AND resource.deletedDate IS MISSING",
                bucketName, DEFAULT_SCOPE, includedResourceType,
                includeQuery.toString(), ftsIndexName
            );
            
            logger.info("📡 Include query: {}", includeQuerySql);
            
            QueryResult includeResult = cluster.query(includeQuerySql);
            List<Map<String, Object>> includeResources = new ArrayList<>();
            
            for (JsonObject row : includeResult.rowsAs(JsonObject.class)) {
                includeResources.add(row.toMap());
            }
            
            logger.info("📋 Found {} {} resources for _revinclude", includeResources.size(), includedResourceType);
            includedResources.put(includedResourceType, includeResources);
            
        } catch (Exception e) {
            logger.error("Failed to process _revinclude parameter {}: {}", revIncludeParam, e.getMessage());
        }
    }
    
    // Helper class for primary search results with IDs
    private static class SearchResultWithIds {
        final List<Map<String, Object>> resources;
        final List<String> resourceIds;
        
        SearchResultWithIds(List<Map<String, Object>> resources, List<String> resourceIds) {
            this.resources = resources;
            this.resourceIds = resourceIds;
        }
    }
} 