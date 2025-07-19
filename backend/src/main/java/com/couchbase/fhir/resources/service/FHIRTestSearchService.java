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
    public List<Map<String, Object>> searchResources(String resourceType, Map<String, String> queryParams, 
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
            
            // Build FTS query - always use SEARCH function
            String ftsQuery = buildFtsSearchQuery(resourceType, queryParams, bucketName);
            logger.info("📡 Executing FTS query: {}", ftsQuery);
            
            QueryResult result = cluster.query(ftsQuery);
            List<Map<String, Object>> resources = processQueryResults(result);
            
            logger.info("📋 Search completed - Found {} {} resources", resources.size(), resourceType);
            return resources;
                
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
    private String buildFtsSearchQuery(String resourceType, Map<String, String> queryParams, String bucketName) {
        // FTS index name: fully qualified <bucket>.Resources.<indexname>
        String ftsIndexName = bucketName + ".Resources.fts" + resourceType;
        
        // Build FTS query JSON
        JsonObject ftsQueryJson = buildFtsQueryJson(resourceType, queryParams);
        
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
     * Build FTS query JSON from search parameters - now with pagination
     */
    private JsonObject buildFtsQueryJson(String resourceType, Map<String, String> queryParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        List<String> ftsConditions = new ArrayList<>();
        
        // Extract pagination parameters
        PaginationParams pagination = extractPaginationParams(queryParams);
        
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String paramName = entry.getKey();
            String value = entry.getValue();
            
            // Skip control parameters (including pagination), but allow _id, _text, and _lastUpdated
            if (paramName.startsWith("_") && !paramName.equals("_text") && !paramName.equals("_id") && !paramName.equals("_lastUpdated")) {
                continue;
            }
            
            processSearchParameter(resourceDef, paramName, value, ftsConditions);
        }
        
        return buildFtsJsonQuery(ftsConditions, pagination);
    }
    
    /**
     * Process individual search parameter
     */
    private void processSearchParameter(RuntimeResourceDefinition resourceDef, String paramName, 
                                      String value, List<String> ftsConditions) {
        
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
        
        // Separate regular conditions from composite conditions
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
            // Match all documents
            mainQuery = JsonObject.create().put("match_all", JsonObject.create());
        } else if (regularConditions.size() == 1 && compositeConditions.isEmpty()) {
            // Single regular condition
            FtsCondition condition = parseFtsCondition(regularConditions.get(0));
            mainQuery = createMatchQuery(condition);
        } else if (regularConditions.isEmpty() && compositeConditions.size() == 1) {
            // Single composite condition
            mainQuery = handleCompositeCondition(compositeConditions.get(0));
        } else {
            // Multiple conditions - use conjuncts for AND logic
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
            
            mainQuery = JsonObject.create().put("conjuncts", conjuncts);
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
     * Create a proper FHIR Bundle response using HAPI FHIR utilities - optimized for performance
     */
    public Bundle createSearchBundle(String resourceType, List<Map<String, Object>> rawResources, 
                                   String baseUrl, Map<String, String> searchParams) {
        logger.info("📦 Creating FHIR Bundle for {} {} resources", rawResources.size(), resourceType);
        
        try {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            bundle.setId(UUID.randomUUID().toString());
            bundle.setTotal(rawResources.size());
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
            List<Bundle.BundleEntryComponent> entries = new ArrayList<>(rawResources.size());
            
            // Reuse ObjectMapper for better performance
            ObjectMapper objectMapper = new ObjectMapper();
            
            // Convert raw resources to FHIR resources
            for (Map<String, Object> rawResource : rawResources) {
                try {
                    // Fast JSON conversion without intermediate string
                    String resourceJson = objectMapper.writeValueAsString(rawResource);
                    IBaseResource fhirResource = jsonParser.parseResource(resourceJson);
                    
                    // Create entry with minimal metadata
                    Bundle.BundleEntryComponent entry = new Bundle.BundleEntryComponent();
                    entry.setResource((Resource) fhirResource);
                    
                    // Set fullUrl (required for search bundles)
                    String resourceId = rawResource.get("id").toString();
                    entry.setFullUrl(baseUrl + "/" + resourceType + "/" + resourceId);
                    
                    // Minimal search metadata
                    Bundle.BundleEntrySearchComponent search = new Bundle.BundleEntrySearchComponent();
                    search.setMode(Bundle.SearchEntryMode.MATCH);
                    entry.setSearch(search);
                    
                    entries.add(entry);
                    
                } catch (Exception e) {
                    logger.error("Failed to process resource {}: {}", rawResource.get("id"), e.getMessage());
                    logger.debug("Resource processing error details:", e);
                    throw new RuntimeException("Resource processing failed: " + e.getMessage());
                }
            }
            
            // Set all entries at once for better performance
            bundle.setEntry(entries);
            
            logger.info("✅ Created Bundle with {} entries", entries.size());
            return bundle;
            
        } catch (Exception e) {
            logger.error("❌ Failed to create FHIR Bundle: {}", e.getMessage());
            logger.debug("Bundle creation error details:", e);
            throw new RuntimeException("Failed to create FHIR Bundle: " + e.getMessage());
        }
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
} 