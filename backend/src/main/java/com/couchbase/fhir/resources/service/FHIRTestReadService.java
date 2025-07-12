package com.couchbase.fhir.resources.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FHIRTestReadService {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestReadService.class);

    @Autowired
    private ConnectionService connectionService;

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

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
     * Get multiple resources by IDs
     */
    public List<Map<String, Object>> getResourcesByIds(String resourceType, List<String> ids,
                                                       String connectionName, String bucketName) {
        try {
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Build document keys
            List<String> documentKeys = ids.stream()
                .map(id -> resourceType + "::" + id)
                .toList();

            String keysString = String.join("','", documentKeys);
            String sql = String.format(
                "SELECT c.* " +
                "FROM `%s`.`%s`.`%s` c " +
                "USE KEYS ['%s']",
                bucketName, DEFAULT_SCOPE, resourceType, keysString
            );

            logger.info("Executing N1QL query for {} resources with {} IDs", resourceType, ids.size());
            
            QueryResult result = cluster.query(sql);
            List<Map<String, Object>> resources = result.rowsAs(JsonObject.class).stream()
                .map(JsonObject::toMap)
                .toList();
            
            logger.info("Successfully retrieved {} out of {} requested {} resources", 
                resources.size(), ids.size(), resourceType);
            return resources;

        } catch (Exception e) {
            logger.error("Failed to get {} resources by IDs: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    // Helper methods
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}
