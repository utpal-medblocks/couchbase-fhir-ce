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

    // FHIR search parameter to field mapping dictionary
    private static final Map<String, SearchParameterMapping> SEARCH_PARAMETER_MAPPINGS = Map.ofEntries(
        Map.entry("telecom", new SearchParameterMapping("telecom.value", true, "telecom.system")),
        Map.entry("phone", new SearchParameterMapping("telecom.value", true, "telecom.system", "phone")),
        Map.entry("email", new SearchParameterMapping("telecom.value", true, "telecom.system", "email")),
        Map.entry("name", new SearchParameterMapping("name.family,name.given", false, null)),
        Map.entry("family", new SearchParameterMapping("name.family", false, null)),
        Map.entry("given", new SearchParameterMapping("name.given", false, null)),
        Map.entry("identifier", new SearchParameterMapping("identifier.value", true, "identifier.system")),
        Map.entry("gender", new SearchParameterMapping("gender", false, null)),
        Map.entry("birthdate", new SearchParameterMapping("birthDate", false, null)),
        Map.entry("active", new SearchParameterMapping("active", false, null)),
        Map.entry("address", new SearchParameterMapping("address.text,address.line,address.city", false, null)),
        Map.entry("organization", new SearchParameterMapping("managingOrganization.reference", false, null)),
        Map.entry("_text", new SearchParameterMapping("", false, null)) // Special case for full-text search
    );

    private static class SearchParameterMapping {
        final String fieldPath;
        final boolean hasSystem;
        final String systemField;
        final String systemValue;
        
        SearchParameterMapping(String fieldPath, boolean hasSystem, String systemField) {
            this(fieldPath, hasSystem, systemField, null);
        }
        
        SearchParameterMapping(String fieldPath, boolean hasSystem, String systemField, String systemValue) {
            this.fieldPath = fieldPath;
            this.hasSystem = hasSystem;
            this.systemField = systemField;
            this.systemValue = systemValue;
        }
    }
    
    public FHIRTestSearchService() {
        this.fhirContext = FhirContext.forR4();
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
                    allConditions.add("SEARCH(t, " + ftsQuery.toString() + ")");
                }
                
                // Add N1QL conditions
                allConditions.addAll(n1qlConditions);
                
                queryBuilder.append(String.join(" AND ", allConditions));
            }
            
            // Add limit
            queryBuilder.append(" LIMIT 50");
            
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
        
        // Get our mapping instead of relying on HAPI's generic path
        SearchParameterMapping mapping = SEARCH_PARAMETER_MAPPINGS.get(parsed.baseName);
        if (mapping == null) {
            logger.warn("Unknown search parameter: {} for resource type: {}", parsed.baseName, resourceDef.getName());
            return;
        }
        
        // Get HAPI parameter for type information
        RuntimeSearchParam searchParam = resourceDef.getSearchParam(parsed.baseName);
        RestSearchParameterTypeEnum paramType = searchParam != null ? searchParam.getParamType() : RestSearchParameterTypeEnum.STRING;
        
        logger.info("🔍 FHIR Search Parameter Details:");
        logger.info("   Parameter: {}", parsed.baseName);
        logger.info("   Value: {}", value);
        logger.info("   Type: {}", paramType);
        logger.info("   Mapped Field Path: {}", mapping.fieldPath);
        logger.info("   Has System: {}", mapping.hasSystem);
        logger.info("   Modifiers: {}", parsed.modifiers);
        
        // Parse value and prefixes
        ParsedValue parsedValue = parseParameterValue(value, paramType);
        
        logger.info("   Parsed Value: {}", parsedValue.value);
        logger.info("   Prefix: {}", parsedValue.prefix);
        
        // Generate query condition based on mapping
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
    
    private void generateQueryConditionFromMapping(SearchParameterMapping mapping,
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
    
    private void handleSystemValueParameter(SearchParameterMapping mapping, String value, String prefix, List<String> ftsConditions) {
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
    
    private void handleImpliedSystemParameter(SearchParameterMapping mapping, String value, String prefix, List<String> ftsConditions) {
        // Create field:type:value conditions for JSON processing
        ftsConditions.add(mapping.systemField + ":match:" + mapping.systemValue);
        ftsConditions.add(mapping.fieldPath + ":match:" + value);  // Use exact match for implied system parameters
        logger.info("   Added FTS system condition: {}:match:{}", mapping.systemField, mapping.systemValue);
        logger.info("   Added FTS value condition: {}:match:{}", mapping.fieldPath, value);
    }
    
    private void handleRegularParameter(SearchParameterMapping mapping, String value, String prefix, 
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
            // Single field
            String fieldPath = mapping.fieldPath;
            
            // Use N1QL for numeric and date comparisons
            if (paramType == RestSearchParameterTypeEnum.NUMBER || paramType == RestSearchParameterTypeEnum.DATE) {
                if (paramType == RestSearchParameterTypeEnum.NUMBER) {
                    handleNumberParameter(fieldPath, value, prefix, n1qlConditions, parameters);
                } else {
                    handleDateParameter(fieldPath, value, prefix, n1qlConditions, parameters);
                }
            } else {
                // Use FTS for text searches
                String condition = createSingleFieldCondition(fieldPath, value, prefix, modifiers, paramType);
                ftsConditions.add(condition);
                logger.info("   Added FTS condition: {}", condition);
            }
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
        } else {
            // Default FHIR prefix search - use wildcard with value*
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