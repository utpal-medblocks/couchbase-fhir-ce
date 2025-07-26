package com.couchbase.fhir.resources.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.json.JsonArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FhirReadService {

    private static final Logger logger = LoggerFactory.getLogger(FhirReadService.class);

    @Autowired
    private ConnectionService connectionService;

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    /**
     * Get resource by ID using FTS with SEARCH function (consistent with FHIRTestSearchService)
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

            // Build FTS query for exact ID match (consistent with Search Service approach)
            String ftsIndexName = bucketName + ".Resources.fts" + resourceType;
            JsonObject ftsQuery = buildFtsQueryForId(id);

            // Build N1QL query using SEARCH function (consistent with FHIRTestSearchService)
            String sql = String.format(
                "SELECT resource.* FROM `%s`.`%s`.`%s` resource " +
                "WHERE SEARCH(resource, %s, {\"index\": \"%s\"}) " +
                "AND resource.deletedDate IS MISSING",
                bucketName, DEFAULT_SCOPE, resourceType, 
                ftsQuery.toString(), ftsIndexName
            );

            logger.info("🔍 Executing FTS query for {}/{}: {}", resourceType, id, sql);
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAs(JsonObject.class);
            
            if (rows.isEmpty()) {
                logger.warn("No active {} found with ID: {}", resourceType, id);
                return null;
            }

            Map<String, Object> resource = rows.get(0).toMap();
            logger.info("✅ Successfully retrieved {} with ID: {} using FTS", resourceType, id);
            return resource;

        } catch (Exception e) {
            logger.error("❌ Failed to get {}/{}: {}", resourceType, id, e.getMessage());
            throw e;
        }
    }

    /**
     * Get multiple resources by IDs using FTS with SEARCH function (consistent with FHIRTestSearchService)
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

            // Build FTS query for multiple IDs using disjuncts (OR logic)
            String ftsIndexName = bucketName + ".Resources.fts" + resourceType;
            JsonObject ftsQuery = buildFtsQueryForIds(ids);

            // Build N1QL query using SEARCH function
            String sql = String.format(
                "SELECT resource.* FROM `%s`.`%s`.`%s` resource " +
                "WHERE SEARCH(resource, %s, {\"index\": \"%s\"}) " +
                "AND resource.deletedDate IS MISSING",
                bucketName, DEFAULT_SCOPE, resourceType,
                ftsQuery.toString(), ftsIndexName
            );

            logger.info("🔍 Executing FTS query for {} resources with {} IDs", resourceType, ids.size());
            
            QueryResult result = cluster.query(sql);
            List<Map<String, Object>> resources = result.rowsAs(JsonObject.class).stream()
                .map(JsonObject::toMap)
                .toList();
            
            logger.info("✅ Successfully retrieved {} out of {} requested active {} resources using FTS", 
                resources.size(), ids.size(), resourceType);
            return resources;

        } catch (Exception e) {
            logger.error("❌ Failed to get {} resources by IDs: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Build FTS query for single ID - exact match on id field
     */
    private JsonObject buildFtsQueryForId(String id) {
        JsonObject query = JsonObject.create();
        JsonObject matchQuery = JsonObject.create();
        matchQuery.put("match", id);
        matchQuery.put("field", "id");
        query.put("query", matchQuery);
        return query;
    }

    /**
     * Build FTS query for multiple IDs - disjuncts (OR) for multiple exact matches
     */
    private JsonObject buildFtsQueryForIds(List<String> ids) {
        JsonObject query = JsonObject.create();
        
        if (ids.size() == 1) {
            // Single ID - use simple match
            JsonObject matchQuery = JsonObject.create();
            matchQuery.put("match", ids.get(0));
            matchQuery.put("field", "id");
            query.put("query", matchQuery);
        } else {
            // Multiple IDs - use disjuncts (OR logic)
            JsonArray disjuncts = JsonArray.create();
            for (String id : ids) {
                JsonObject idMatch = JsonObject.create();
                idMatch.put("match", id);
                idMatch.put("field", "id");
                disjuncts.add(idMatch);
            }
            
            JsonObject disjunctQuery = JsonObject.create();
            disjunctQuery.put("disjuncts", disjuncts);
            query.put("query", disjunctQuery);
        }
        
        return query;
    }

    // Helper methods
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}
