package com.couchbase.fhir.search.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FHIRTestSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(FHIRTestSearchService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    private final FhirContext fhirContext;
    
    // Default connection and bucket names
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";
    
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
        // Status fields
        "clinicalStatus", "verificationStatus", "status",
        // Classification  
        "category", "type", "class", "code",
        // Severity/Priority
        "criticality", "severity", "priority", "intent",
        // Administrative
        "maritalStatus", "use"
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
    
    public FHIRTestSearchService() {
        this.fhirContext = FhirContext.forR4();
        addUSCoreSearchParameters();
    }
    
    private void addUSCoreSearchParameters() {
        // US Core parameters are handled manually in handleUSCoreParameters()
        logger.info("US Core search parameters handled manually");
    }
    
    /**
     * Handle common US Core search parameters that are not in base FHIR R4
     */
    private FieldPathMapping handleUSCoreParameters(String paramName, String resourceType) {
        if ("Patient".equals(resourceType)) {
            switch (paramName) {
                case "language":
                    // Patient communication language (CodeableConcept)
                    return new FieldPathMapping("communication.language.coding.code", 
                                               "communication.language.coding.system", null, true);
                case "marital-status":
                    // Patient marital status (CodeableConcept) 
                    return new FieldPathMapping("maritalStatus.coding.code", 
                                               "maritalStatus.coding.system", null, true);
                // Add more US Core Patient parameters as needed
                default:
                    return null;
            }
        }
        // Add support for other resource types as needed
        return null;
    }
    
    /**
     * Resolve field path from HAPI search parameter using the patterns we discovered:
     * - TOKEN type: add ".value" to base path, handle system field
     * - STRING type: use path directly
     * - Special filtered cases: handle "where(system='phone')" patterns
     */
    private FieldPathMapping resolveFieldPathFromHapi(RuntimeSearchParam searchParam) {
        String hapiPath = searchParam.getPath();
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        logger.info("🔧 Resolving field path from HAPI:");
        logger.info("   HAPI Path: {}", hapiPath);
        logger.info("   Param Type: {}", paramType);
        
        // Handle special case for full-text search
        if ("_text".equals(searchParam.getName())) {
            return new FieldPathMapping("", null, null, false);
        }
        
        // Convert Patient.xxx to xxx (remove resource type prefix)
        String basePath = convertToFieldPath(hapiPath);
        
        if (paramType == RestSearchParameterTypeEnum.TOKEN) {
            return handleTokenFieldPath(basePath, hapiPath, searchParam.getName());
        } else {
            // STRING, REFERENCE, DATE, NUMBER, etc. - use path directly
            return new FieldPathMapping(basePath, null, null, false);
        }
    }
    
    private String convertToFieldPath(String hapiPath) {
        // "Patient.name.given" → "name.given"
        // "Patient.telecom.where(system='phone')" → "telecom.where(system='phone')"
        return hapiPath.replaceFirst("^[^.]+\\.", "");
    }
    
    private FieldPathMapping handleTokenFieldPath(String basePath, String hapiPath, String paramName) {
        // Handle special filtered cases like "Patient.telecom.where(system='phone')"
        if (hapiPath.contains(".where(system=")) {
            return handleFilteredTokenPath(basePath, hapiPath);
        } else {
            // Check if this is a CodeableConcept field
            if (CODEABLE_CONCEPT_FIELDS.contains(basePath)) {
                // CodeableConcept TOKEN: "clinicalStatus" → "clinicalStatus.coding.code" with coding system field
                logger.info("   🎯 Detected CodeableConcept field: {}", basePath);
                String fieldPath = basePath + ".coding.code";
                String systemField = basePath + ".coding.system";
                return new FieldPathMapping(fieldPath, systemField, null, true);
            } else {
                // Simple TOKEN: "Patient.telecom" → "telecom.value" with system field
                logger.info("   🔗 Detected simple TOKEN field: {}", basePath);
                String fieldPath = basePath + ".value";
                String systemField = basePath + ".system";
                return new FieldPathMapping(fieldPath, systemField, null, true);
            }
        }
    }
    
    private FieldPathMapping handleFilteredTokenPath(String basePath, String hapiPath) {
        // Extract system value from "Patient.telecom.where(system='phone')"
        // Pattern: "Patient.telecom.where(system='phone')" → system="phone"
        String systemValue = null;
        int whereIndex = hapiPath.indexOf(".where(system=");
        if (whereIndex != -1) {
            int startQuote = hapiPath.indexOf("'", whereIndex);
            int endQuote = hapiPath.indexOf("'", startQuote + 1);
            if (startQuote != -1 && endQuote != -1) {
                systemValue = hapiPath.substring(startQuote + 1, endQuote);
            }
        }
        
        // Remove the .where() part to get base path
        String cleanBasePath = basePath.replaceFirst("\\.where\\(.*\\)", "");
        String fieldPath = cleanBasePath + ".value";
        String systemField = cleanBasePath + ".system";
        
        return new FieldPathMapping(fieldPath, systemField, systemValue, true);
    }
    
    public List<Map<String, Object>> searchResources(String resourceType, Map<String, String> queryParams, 
                                                    String connectionName, String bucketName) {
        try {
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }
            
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT * FROM `")
                       .append(bucketName).append("`.`").append(DEFAULT_SCOPE).append("`.`")
                       .append(resourceType).append("` t");
            
            List<String> ftsConditions = new ArrayList<>();
            List<String> n1qlConditions = new ArrayList<>();
            JsonObject parameters = JsonObject.create();
            
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String paramName = entry.getKey();
                String value = entry.getValue();
                
                // Skip control parameters
                if (paramName.startsWith("_") && !paramName.equals("_text")) {
                    continue;
                }
                
                processSearchParameter(resourceDef, paramName, value, ftsConditions, n1qlConditions, parameters);
            }
            
            // Add WHERE clause only if we have conditions
            boolean hasConditions = !ftsConditions.isEmpty() || !n1qlConditions.isEmpty();
            if (hasConditions) {
                queryBuilder.append(" WHERE ");
                
                List<String> allConditions = new ArrayList<>();
                
                // Add FTS conditions using JSON-based conjuncts approach
                if (!ftsConditions.isEmpty()) {
                    JsonObject ftsQuery = buildFtsJsonQuery(ftsConditions);
                    String indexName = bucketName + ".Resources.fts" + resourceType;
                    JsonObject indexSpec = JsonObject.create().put("index", indexName);
                    allConditions.add("SEARCH(t, " + ftsQuery.toString() + ", " + indexSpec.toString() + ")");
                }
                
                // Add N1QL conditions
                allConditions.addAll(n1qlConditions);
                
                queryBuilder.append(String.join(" AND ", allConditions));
            }
            
            // Note: Size/limit is now handled inside FTS query, not in SQL LIMIT clause
            
            String finalQuery = queryBuilder.toString();
            logger.info("Executing search query: {}", finalQuery);
            
            QueryResult result = cluster.query(finalQuery, QueryOptions.queryOptions().parameters(parameters));
            
            List<Map<String, Object>> resources = new ArrayList<>();
            for (JsonObject row : result.rowsAs(JsonObject.class)) {
                Map<String, Object> resource = row.toMap();
                
                // Extract ID from document key if it exists in the format ResourceType::id
                if (resource.containsKey("META") && resource.get("META") instanceof Map) {
                    Map<String, Object> meta = (Map<String, Object>) resource.get("META");
                    if (meta.containsKey("id")) {
                        String documentKey = (String) meta.get("id");
                        if (documentKey.contains("::")) {
                            String resourceId = documentKey.split("::", 2)[1];
                            resource.put("id", resourceId);
                        }
                    }
                }
                
                resources.add(resource);
            }
            
            logger.info("Successfully retrieved {} {} resources from search", resources.size(), resourceType);
            return resources;
                
        } catch (Exception e) {
            logger.error("Failed to search {} resources: {}", resourceType, e.getMessage(), e);
            throw new RuntimeException("Search failed", e);
        }
    }
    
    private void processSearchParameter(RuntimeResourceDefinition resourceDef, 
                                      String paramName, 
                                      String value,
                                      List<String> ftsConditions,
                                      List<String> n1qlConditions,
                                      JsonObject parameters) {
        
        // Parse parameter name and modifiers
        ParsedParameter parsed = parseParameterName(paramName);
        
        // Get HAPI parameter for dynamic field path resolution
        RuntimeSearchParam searchParam = resourceDef.getSearchParam(parsed.baseName);
        
        FieldPathMapping mapping;
        RestSearchParameterTypeEnum paramType;
        
        if (searchParam == null) {
            // Handle common US Core parameters manually
            mapping = handleUSCoreParameters(parsed.baseName, resourceDef.getName());
            if (mapping == null) {
                logger.warn("Unknown search parameter: {} for resource type: {}", parsed.baseName, resourceDef.getName());
                return;
            }
            paramType = RestSearchParameterTypeEnum.TOKEN; // Most US Core params are TOKEN
        } else {
            paramType = searchParam.getParamType();
            
            // Check if we need to override HAPI's path for specific US Core parameters
            FieldPathMapping usCoreOverride = handleUSCoreParameters(parsed.baseName, resourceDef.getName());
            if (usCoreOverride != null) {
                logger.info("   🇺🇸 Using US Core override for parameter: {}", parsed.baseName);
                mapping = usCoreOverride;
            } else {
                // Resolve field path dynamically from HAPI
                mapping = resolveFieldPathFromHapi(searchParam);
            }
        }
        
        logger.info("🔍 FHIR Search Parameter Details:");
        logger.info("   Parameter: {}", parsed.baseName);
        logger.info("   Value: {}", value);
        logger.info("   Type: {}", paramType);
        logger.info("   🏷️  HAPI Path: {}", searchParam != null ? searchParam.getPath() : "US Core - manual");
        logger.info("   📋 Resolved Field Path: {}", mapping.fieldPath);
        logger.info("   Has System: {}", mapping.hasSystem);
        logger.info("   System Field: {}", mapping.systemField);
        logger.info("   System Value: {}", mapping.systemValue);
        logger.info("   Modifiers: {}", parsed.modifiers);
        
        // Parse value and prefixes
        ParsedValue parsedValue = parseParameterValue(value, paramType);
        
        logger.info("   Parsed Value: {}", parsedValue.value);
        logger.info("   Prefix: {}", parsedValue.prefix);
        
        // Generate query condition based on dynamic mapping
        generateQueryConditionFromMapping(mapping, parsedValue, parsed, paramType, ftsConditions, n1qlConditions, parameters);
    }
    
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
    
    private ParsedValue parseParameterValue(String value, RestSearchParameterTypeEnum paramType) {
        // Check for prefix operators (gt, lt, etc.)
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
    
    private void generateQueryConditionFromMapping(FieldPathMapping mapping,
                                                 ParsedValue parsedValue, 
                                                 ParsedParameter parsed,
                                                 RestSearchParameterTypeEnum paramType,
                                                 List<String> ftsConditions,
                                                 List<String> n1qlConditions,
                                                 JsonObject parameters) {
        
        String value = parsedValue.value;
        String prefix = parsedValue.prefix;
        
        logger.info("🔧 Generating Query Condition:");
        logger.info("   Mapping Field Path: {}", mapping.fieldPath);
        logger.info("   Parameter Type: {}", paramType);
        logger.info("   Value: {}", value);
        logger.info("   Prefix: {}", prefix);
        
        // Handle special cases first
        if (parsed.baseName.equals("_text")) {
            ftsConditions.add(value);
            logger.info("   Added FTS condition: {}", value);
            return;
        }
        
        if (parsed.modifiers.contains("missing")) {
            handleMissingModifier(mapping.fieldPath, value, n1qlConditions);
            return;
        }
        
        if (parsed.modifiers.contains("not")) {
            prefix = "ne";
        }
        
        // Handle system|value format for parameters that support it
        if (mapping.hasSystem && value.contains("|")) {
            handleSystemValueParameter(mapping, value, prefix, ftsConditions);
        } else if (mapping.hasSystem && mapping.systemValue != null) {
            // Parameter like "phone" that implies system=phone
            handleImpliedSystemParameter(mapping, value, prefix, ftsConditions);
        } else {
            // Regular parameter handling
            handleRegularParameter(mapping, value, prefix, parsed.modifiers, paramType, ftsConditions, n1qlConditions, parameters);
        }
    }
    
    private void handleSystemValueParameter(FieldPathMapping mapping, String value, String prefix, List<String> ftsConditions) {
        String[] parts = value.split("\\|", 2);
        String system = parts[0];
        String code = parts[1];
        
        // Create field:type:value conditions for JSON processing
        if (!system.isEmpty() && !code.isEmpty()) {
            ftsConditions.add(mapping.systemField + ":match:" + system);
            ftsConditions.add(mapping.fieldPath + ":match:" + code);  // Use exact match for system|value format
            logger.info("   Added FTS system condition: {}:match:{}", mapping.systemField, system);
            logger.info("   Added FTS value condition: {}:match:{}", mapping.fieldPath, code);
        } else if (!code.isEmpty()) {
            ftsConditions.add(mapping.fieldPath + ":match:" + code);
            logger.info("   Added FTS condition: {}:match:{}", mapping.fieldPath, code);
        } else {
            ftsConditions.add(mapping.systemField + ":match:" + system);
            logger.info("   Added FTS condition: {}:match:{}", mapping.systemField, system);
        }
    }
    
    private void handleImpliedSystemParameter(FieldPathMapping mapping, String value, String prefix, List<String> ftsConditions) {
        // Create field:type:value conditions for JSON processing
        ftsConditions.add(mapping.systemField + ":match:" + mapping.systemValue);
        ftsConditions.add(mapping.fieldPath + ":match:" + value);  // Use exact match for implied system parameters
        logger.info("   Added FTS system condition: {}:match:{}", mapping.systemField, mapping.systemValue);
        logger.info("   Added FTS value condition: {}:match:{}", mapping.fieldPath, value);
    }
    
    private void handleRegularParameter(FieldPathMapping mapping, String value, String prefix, 
                                      Set<String> modifiers, RestSearchParameterTypeEnum paramType,
                                      List<String> ftsConditions, List<String> n1qlConditions, JsonObject parameters) {
        
        // Handle multiple field paths (e.g., "name.family,name.given")
        String[] fieldPaths = mapping.fieldPath.split(",");
        
        if (fieldPaths.length > 1) {
            // Multiple fields - for now, just use the first field (we can enhance this later)
            String fieldPath = fieldPaths[0].trim();
            String condition = createSingleFieldCondition(fieldPath, value, prefix, modifiers, paramType);
            ftsConditions.add(condition);
            logger.info("   Added FTS condition: {}", condition);
        } else {
            // Single field - always use FTS for all parameter types
            String fieldPath = mapping.fieldPath;
            String condition = createSingleFieldCondition(fieldPath, value, prefix, modifiers, paramType);
            ftsConditions.add(condition);
            logger.info("   Added FTS condition: {}", condition);
        }
    }
    
    private String createSingleFieldCondition(String fieldPath, String value, String prefix, Set<String> modifiers, RestSearchParameterTypeEnum paramType) {
        // Create field:type:value format for different search behaviors
        if (modifiers.contains("exact")) {
            // Exact match - use original value with match
            return fieldPath + ":match:" + value;
        } else if (modifiers.contains("contains")) {
            // Contains search - use wildcard with *value*
            return fieldPath + ":wildcard:*" + value.toLowerCase() + "*";
        } else if (paramType == RestSearchParameterTypeEnum.DATE || paramType == RestSearchParameterTypeEnum.NUMBER) {
            // For dates and numbers, use FTS range queries with prefix operators
            // Include parameter type for proper range query generation
            return fieldPath + ":range:" + paramType.name() + ":" + prefix + ":" + value;
        } else {
            // Default FHIR prefix search for strings - use wildcard with value*
            return fieldPath + ":wildcard:" + value.toLowerCase() + "*";
        }
    }
    
    private void handleNumberParameter(String fieldPath, String value, String prefix, 
                                     List<String> n1qlConditions, JsonObject parameters) {
        try {
            double numValue = Double.parseDouble(value);
            String paramName = fieldPath.replace(".", "_") + "_num_" + System.currentTimeMillis();
            String operator = PREFIX_OPERATORS.getOrDefault(prefix, "=");
            
            n1qlConditions.add("t." + fieldPath + " " + operator + " $" + paramName);
            parameters.put(paramName, numValue);
        } catch (NumberFormatException e) {
            logger.warn("Invalid number format: {}", value);
        }
    }
    
    private void handleDateParameter(String fieldPath, String value, String prefix, 
                                   List<String> n1qlConditions, JsonObject parameters) {
        try {
            // Try to parse as date
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            String paramName = fieldPath.replace(".", "_") + "_date_" + System.currentTimeMillis();
            String operator = PREFIX_OPERATORS.getOrDefault(prefix, "=");
            
            n1qlConditions.add("t." + fieldPath + " " + operator + " $" + paramName);
            parameters.put(paramName, value);
        } catch (DateTimeParseException e) {
            logger.warn("Invalid date format: {}", value);
        }
    }
    
    private void handleReferenceParameter(String fieldPath, String value, List<String> ftsConditions) {
        // Handle reference searches like "patient.id" or "Patient/123"
        if (value.startsWith("http://") || value.startsWith("https://")) {
            ftsConditions.add(fieldPath + ":\"" + escapeValue(value) + "\"");
        } else if (value.contains("/")) {
            ftsConditions.add(fieldPath + ":\"" + escapeValue(value) + "\"");
        } else {
            // Assume it's just an ID
            ftsConditions.add(fieldPath + ":*" + escapeValue(value) + "*");
        }
    }
    
    private void handleDefaultParameter(String fieldPath, String value, String prefix, List<String> ftsConditions) {
        String condition = fieldPath + ":\"" + escapeValue(value) + "\"";
        
        if ("ne".equals(prefix)) {
            condition = "-(" + condition + ")";
        }
        
        ftsConditions.add(condition);
    }
    
    private void handleMissingModifier(String fieldPath, String value, List<String> n1qlConditions) {
        boolean missing = "true".equalsIgnoreCase(value);
        
        if (missing) {
            n1qlConditions.add("t." + fieldPath + " IS MISSING");
        } else {
            n1qlConditions.add("t." + fieldPath + " IS NOT MISSING");
        }
    }
    
    private String escapeValue(String value) {
        // Escape special characters for FTS
        return value.replace("\"", "\\\"");
    }
    
    private void handleRangeQuery(JsonObject matchQuery, String field, String value) {
        // Parse paramType:prefix:value format (e.g., "DATE:lt:2020-04-18" or "NUMBER:ge:150")
        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            // Fallback to exact match if format is incorrect
            matchQuery.put("match", value);
            matchQuery.put("field", field);
            return;
        }
        
        String paramType = parts[0];
        String prefix = parts[1];
        String actualValue = parts[2];
        
        matchQuery.put("field", field);
        
        if ("DATE".equals(paramType)) {
            // Use dateRange queries for DATE parameters
            handleDateRangeQuery(matchQuery, prefix, actualValue);
        } else {
            // Use numeric range queries for NUMBER parameters
            handleNumberRangeQuery(matchQuery, prefix, actualValue);
        }
    }
    
    private void handleDateRangeQuery(JsonObject matchQuery, String prefix, String dateValue) {
        // Create FTS dateRange query based on prefix following the pattern:
        // dateRangeQuery(start, end, inclusive_start, inclusive_end)
        switch (prefix) {
            case "eq":
                // Same start/end with both inclusive: dateRangeQuery(date, date, true, true)
                matchQuery.put("start", dateValue);
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_start", true);
                matchQuery.put("inclusive_end", true);
                break;
            case "ge": 
                // Greater than or equal: dateRangeQuery(date, null, true, false)
                matchQuery.put("start", dateValue);
                matchQuery.put("inclusive_start", true);
                // No end date, so no inclusive_end
                break;
            case "gt":
            case "sa": // starts after (dates)
                // Greater than: dateRangeQuery(date, null, false, false)
                matchQuery.put("start", dateValue);
                matchQuery.put("inclusive_start", false);
                // No end date, so no inclusive_end
                break;
            case "le":
                // Less than or equal: dateRangeQuery(null, date, false, true)
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_end", true);
                // No start date, so no inclusive_start
                break;
            case "lt":
            case "eb": // ends before (dates)
                // Less than: dateRangeQuery(null, date, false, false)
                matchQuery.put("end", dateValue);
                matchQuery.put("inclusive_end", false);
                // No start date, so no inclusive_start
                break;
            default:
                // Unknown prefix - fallback to exact match
                matchQuery.put("match", dateValue);
                break;
        }
    }
    
    private void handleNumberRangeQuery(JsonObject matchQuery, String prefix, String numberValue) {
        // Create FTS numeric range query based on prefix
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
                // Equal or unknown prefix - use exact match
                matchQuery.put("match", numberValue);
                break;
        }
    }
    
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
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

    private JsonObject buildFtsJsonQuery(List<String> ftsConditions) {
        JsonObject query = JsonObject.create();
        JsonObject queryRoot = JsonObject.create();
        
        if (ftsConditions.size() == 1) {
            // Single condition - parse and use directly
            FtsCondition condition = parseFtsCondition(ftsConditions.get(0));
            JsonObject singleQuery = createMatchQuery(condition);
            query.put("query", singleQuery);
        } else {
            // Multiple conditions - use conjuncts (all must match)
            JsonArray conjuncts = JsonArray.create();
            
            for (String conditionStr : ftsConditions) {
                FtsCondition condition = parseFtsCondition(conditionStr);
                JsonObject conjunct = createMatchQuery(condition);
                conjuncts.add(conjunct);
            }
            
            queryRoot.put("conjuncts", conjuncts);
            query.put("query", queryRoot);
        }
        
        // Add size parameter to FTS query (replaces SQL LIMIT clause)
        query.put("size", 50);
        
        return query;
    }
    
    private JsonObject createMatchQuery(FtsCondition condition) {
        JsonObject matchQuery = JsonObject.create();
        
        if (condition.field.isEmpty()) {
            // Full-text search without specific field
            matchQuery.put("match", condition.value);
            return matchQuery;
        }
        
        // Parse type:value format (field is already separated)
        String[] parts = condition.value.split(":", 2);
        if (parts.length != 2) {
            // Fallback to simple match if format is incorrect
            matchQuery.put("match", condition.value);
            matchQuery.put("field", condition.field);
            return matchQuery;
        }
        
        String type = parts[0];
        String value = parts[1];
        
        switch (type) {
            case "match":
                // Exact match - use original value
                matchQuery.put("match", value);
                matchQuery.put("field", condition.field);
                break;
                
            case "wildcard":
                // Wildcard search - use wildcard query
                matchQuery.put("wildcard", value);
                matchQuery.put("field", condition.field);
                break;
                
            case "range":
                // Range query for dates and numbers with prefix operators
                handleRangeQuery(matchQuery, condition.field, value);
                break;
                
            default:
                // Unknown type, fallback to simple match
                matchQuery.put("match", value);
                matchQuery.put("field", condition.field);
        }
        
        return matchQuery;
    }
    
    private FtsCondition parseFtsCondition(String condition) {
        // Parse conditions like "telecom.system:match:phone" or "name.family:wildcard:smith*"
        if (!condition.contains(":")) {
            // Full-text search without field
            return new FtsCondition("", condition);
        }
        
        // Split into field and type:value
        String[] parts = condition.split(":", 3);
        if (parts.length != 3) {
            // Fallback for malformed conditions
            return new FtsCondition("", condition);
        }
        
        String field = parts[0];
        String type = parts[1];
        String value = parts[2];
        
        // Return field and type:value (createMatchQuery will parse the type)
        return new FtsCondition(field, type + ":" + value);
    }
    
    // Helper class to hold FTS condition components
    private static class FtsCondition {
        final String field;
        final String value;
        
        FtsCondition(String field, String value) {
            this.field = field;
            this.value = value;
        }
    }
} 