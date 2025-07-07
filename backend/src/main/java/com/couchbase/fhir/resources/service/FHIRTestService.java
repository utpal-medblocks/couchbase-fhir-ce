package com.couchbase.fhir.resources.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FHIRTestService {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestService.class);

    @Autowired
    private ConnectionService connectionService;

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    /**
     * Get capabilities from actual Couchbase data
     */
    public Map<String, Object> getCapabilities() {
        try {
            // Get list of active connections
            List<String> connections = connectionService.getActiveConnections();
            String connectionName = connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return getDefaultCapabilities();
            }

            // Query to get available resource types (collections)
            String sql = String.format(
                "SELECT DISTINCT c.name as collectionName " +
                "FROM system:keyspaces c " +
                "WHERE c.`bucket` = '%s' AND c.`scope` = '%s'",
                DEFAULT_BUCKET, DEFAULT_SCOPE
            );

            Set<String> resourceTypes = new HashSet<>();
            try {
                QueryResult result = cluster.query(sql);
                result.rowsAs(JsonObject.class).forEach(row -> {
                    resourceTypes.add(row.getString("collectionName"));
                });
            } catch (Exception e) {
                logger.warn("Could not query collections, using defaults: {}", e.getMessage());
                resourceTypes.addAll(Arrays.asList("Patient", "Observation", "Encounter", "Condition"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("fhirVersion", "4.0.1");
            response.put("supportedResources", resourceTypes);
            response.put("interactions", new String[]{"read", "create", "search-type"});
            response.put("multiTenant", true);
            response.put("totalResourceTypes", resourceTypes.size());
            response.put("dataSource", "Couchbase - " + connectionName);
            response.put("timestamp", getCurrentFhirTimestamp());
            
            return response;
        } catch (Exception e) {
            logger.error("Failed to get capabilities from Couchbase: {}", e.getMessage());
            return getDefaultCapabilities();
        }
    }

    /**
     * Search resources using N1QL query
     */
    public Map<String, Object> searchResources(String resourceType, String connectionName, 
                                             String bucketName, Map<String, String> searchParams) {
        try {
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Build N1QL query
            String sql = String.format(
                "SELECT META().id as documentId, c.* " +
                "FROM `%s`.`%s`.`%s` c " +
                "WHERE c.resourceType = '%s' " +
                "LIMIT 20",
                bucketName, DEFAULT_SCOPE, resourceType, resourceType
            );

            logger.info("Executing N1QL query: {}", sql);
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            // Create FHIR Bundle response
            Map<String, Object> bundle = new HashMap<>();
            bundle.put("resourceType", "Bundle");
            bundle.put("id", resourceType.toLowerCase() + "-search-" + System.currentTimeMillis());
            bundle.put("type", "searchset");
            bundle.put("total", rows.size());
            bundle.put("timestamp", getCurrentFhirTimestamp());

            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonObject row : rows) {
                String documentKey = row.getString("documentId"); // This will be ResourceType::id
                JsonObject resource = row.removeKey("documentId"); // Remove our added field
                
                // Extract just the ID part from ResourceType::id
                String resourceId = documentKey.contains("::") ? 
                    documentKey.split("::", 2)[1] : documentKey;
                
                Map<String, Object> entry = new HashMap<>();
                entry.put("fullUrl", String.format("http://localhost:8080/api/fhir-test/%s/%s/%s", bucketName, resourceType, resourceId));
                entry.put("resource", resource.toMap());
                entries.add(entry);
            }
            bundle.put("entry", entries);

            logger.info("Successfully retrieved {} {} resources from Couchbase", rows.size(), resourceType);
            return bundle;

        } catch (Exception e) {
            logger.error("Failed to search {} resources: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Get resource by ID using N1QL query
     */
    public Map<String, Object> getResourceById(String resourceType, String id, 
                                             String connectionName, String bucketName) {
        try {
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Build N1QL query to get specific document using ResourceType::id format
            String documentKey = resourceType + "::" + id;
            String sql = String.format(
                "SELECT c.* " +
                "FROM `%s`.`%s`.`%s` c " +
                "USE KEYS '%s'",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey
            );

            logger.info("Executing N1QL query for {}/{}: {}", resourceType, id, sql);
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            if (rows.isEmpty()) {
                logger.warn("No {} found with ID: {}", resourceType, id);
                return null;
            }

            Map<String, Object> resource = rows.get(0).toMap();
            logger.info("Successfully retrieved {} with ID: {}", resourceType, id);
            return resource;

        } catch (Exception e) {
            logger.error("Failed to get {}/{}: {}", resourceType, id, e.getMessage());
            throw e;
        }
    }

    /**
     * Create resource using N1QL INSERT
     */
    public Map<String, Object> createResource(String resourceType, String connectionName, 
                                            String bucketName, Map<String, Object> resourceData) {
        try {
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Generate ID if not provided
            String resourceId = resourceData.containsKey("id") ? 
                resourceData.get("id").toString() : 
                resourceType.toLowerCase() + "-" + System.currentTimeMillis();
            
            // Create document key with ResourceType::id format
            String documentKey = resourceType + "::" + resourceId;
            
            // Set resource metadata
            resourceData.put("resourceType", resourceType);
            resourceData.put("id", resourceId);
            
            // Add meta information
            Map<String, Object> meta = new HashMap<>();
            meta.put("versionId", "1");
            meta.put("lastUpdated", getCurrentFhirTimestamp());
            meta.put("profile", Arrays.asList("http://hl7.org/fhir/StructureDefinition/" + resourceType));
            resourceData.put("meta", meta);

            // Build N1QL INSERT query
            String sql = String.format(
                "INSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey, 
                JsonObject.from(resourceData).toString()
            );

            logger.info("Executing N1QL INSERT for {}: {}", resourceType, resourceId);
            
            QueryResult result = cluster.query(sql);
            
            // Create response
            Map<String, Object> response = new HashMap<>();
            response.put("id", resourceId);
            response.put("resourceType", resourceType);
            response.put("created", getCurrentFhirTimestamp());
            response.put("location", "/api/fhir-test/" + bucketName + "/" + resourceType + "/" + resourceId);
            response.put("status", "created");
            
            logger.info("Successfully created {} with ID: {}", resourceType, resourceId);
            return response;

        } catch (Exception e) {
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    // Helper methods
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }

    private Map<String, Object> getDefaultCapabilities() {
        Map<String, Object> response = new HashMap<>();
        response.put("fhirVersion", "4.0.1");
        response.put("supportedResources", Arrays.asList("Patient", "Observation", "Encounter", "Condition"));
        response.put("interactions", new String[]{"read", "create", "search-type"});
        response.put("multiTenant", true);
        response.put("totalResourceTypes", 4);
        response.put("dataSource", "Couchbase - No active connection");
        response.put("error", "No active Couchbase connection available");
        response.put("timestamp", getCurrentFhirTimestamp());
        return response;
    }

    private String getCurrentFhirTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }
} 