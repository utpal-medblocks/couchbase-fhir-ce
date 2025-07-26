package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FhirCapabilityService {

    private static final Logger logger = LoggerFactory.getLogger(FhirCapabilityService.class);

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirContext fhirContext;  // ✅ Inject the configured context

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    public FhirCapabilityService() {
        // Empty - Spring will inject dependencies
    }
    
    @PostConstruct
    private void init() {
        logger.info("🚀 FhirCapabilityService initialized with FHIR R4 context");
        
        // Configure FHIR context for optimal performance
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false);
        
        logger.info("✅ FHIR General Service optimized for capabilities and metadata operations");
    }

    /**
     * Get capabilities statement from actual Couchbase data
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
            response.put("interactions", new String[]{"read", "create", "update", "delete", "search-type"});
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
     * Get server metadata and health status
     */
    public Map<String, Object> getMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        
        try {
            List<String> connections = connectionService.getActiveConnections();
            String connectionName = connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
            
            metadata.put("serverName", "Couchbase FHIR Community Edition");
            metadata.put("fhirVersion", "4.0.1");
            metadata.put("activeConnections", connections.size());
            metadata.put("defaultBucket", DEFAULT_BUCKET);
            metadata.put("defaultScope", DEFAULT_SCOPE);
            metadata.put("timestamp", getCurrentFhirTimestamp());
            
            // Add connection health status
            Cluster cluster = connectionService.getConnection(connectionName);
            metadata.put("databaseHealth", cluster != null ? "connected" : "disconnected");
            
        } catch (Exception e) {
            logger.error("Failed to get metadata: {}", e.getMessage());
            metadata.put("error", e.getMessage());
            metadata.put("databaseHealth", "error");
        }
        
        return metadata;
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
        response.put("interactions", new String[]{"read", "create", "update", "delete", "search-type"});
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
