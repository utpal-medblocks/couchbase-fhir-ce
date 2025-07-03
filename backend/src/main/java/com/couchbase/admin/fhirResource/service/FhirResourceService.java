package com.couchbase.admin.fhirResource.service;

import com.couchbase.admin.fhirResource.model.DocumentKeyRequest;
import com.couchbase.admin.fhirResource.model.DocumentKeyResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class FhirResourceService {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirResourceService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Get document keys for a specific FHIR collection with pagination
     */
    public DocumentKeyResponse getDocumentKeys(DocumentKeyRequest request, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            // Calculate offset for pagination
            int offset = request.getPage() * request.getPageSize();
            
            // Build the SQL query
            String sql = buildDocumentKeysQuery(request, offset);
            
            // Execute query with timeout
            var result = cluster.query(sql, QueryOptions.queryOptions()
                .timeout(Duration.ofSeconds(30)));
            
            // Extract document keys from result
            List<String> documentKeys = new ArrayList<>();
            for (var row : result.rowsAs(String.class)) {
                documentKeys.add(row);
            }
            
            // Get total count (separate query for accurate pagination)
            int totalCount = getTotalDocumentCount(cluster, request);
            
            // Calculate if there are more pages
            boolean hasMore = (offset + request.getPageSize()) < totalCount;
            
            return new DocumentKeyResponse(
                request.getBucketName(),
                request.getCollectionName(),
                documentKeys,
                totalCount,
                request.getPage(),
                request.getPageSize(),
                hasMore
            );
            
        } catch (Exception e) {
            logger.error("Failed to get document keys for bucket: {}, collection: {}", 
                        request.getBucketName(), request.getCollectionName(), e);
            throw new RuntimeException("Failed to fetch document keys: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build the SQL query for fetching document keys
     */
    private String buildDocumentKeysQuery(DocumentKeyRequest request, int offset) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT RAW META().id FROM `")
           .append(request.getBucketName())
           .append("`.`Resources`.`")
           .append(request.getCollectionName())
           .append("`");
        
        // Add WHERE clause for patient filtering if specified
        if (request.getPatientId() != null && !request.getPatientId().trim().isEmpty()) {
            if ("Patient".equals(request.getCollectionName())) {
                // For Patient collection, filter by the document key itself
                sql.append(" WHERE META().id LIKE '")
                   .append(request.getPatientId())
                   .append("%'");
            } else {
                // For other collections, filter by patient reference
                sql.append(" WHERE ANY ref IN OBJECT_PAIRS(SELF) SATISFIES ")
                   .append("ref.`val`.reference LIKE 'Patient/")
                   .append(request.getPatientId())
                   .append("' END");
            }
        }
        
        // Add ORDER BY for consistent pagination
        sql.append(" ORDER BY META().id");
        
        // Add LIMIT and OFFSET
        sql.append(" LIMIT ").append(request.getPageSize())
           .append(" OFFSET ").append(offset);
        
        return sql.toString();
    }
    
    /**
     * Get total count of documents for pagination
     */
    private int getTotalDocumentCount(Cluster cluster, DocumentKeyRequest request) {
        try {
            StringBuilder countSql = new StringBuilder();
            countSql.append("SELECT COUNT(*) as count FROM `")
                    .append(request.getBucketName())
                    .append("`.`Resources`.`")
                    .append(request.getCollectionName())
                    .append("`");
            
            // Add same WHERE clause as main query
            if (request.getPatientId() != null && !request.getPatientId().trim().isEmpty()) {
                if ("Patient".equals(request.getCollectionName())) {
                    countSql.append(" WHERE META().id LIKE '")
                            .append(request.getPatientId())
                            .append("%'");
                } else {
                    countSql.append(" WHERE ANY ref IN OBJECT_PAIRS(SELF) SATISFIES ")
                            .append("ref.`val`.reference LIKE 'Patient/")
                            .append(request.getPatientId())
                            .append("' END");
                }
            }
            
            var result = cluster.query(countSql.toString(), QueryOptions.queryOptions()
                .timeout(Duration.ofSeconds(30)));
            
            if (result.rowsAsObject().size() > 0) {
                return result.rowsAsObject().get(0).getInt("count");
            }
            
            return 0;
            
        } catch (Exception e) {
            logger.warn("Failed to get total count, returning 0: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get a specific document by its key
     */
    public Object getDocument(String bucketName, String collectionName, String documentKey, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            // Use KV operation to get the document directly
            var bucket = cluster.bucket(bucketName);
            var scope = bucket.scope("Resources");
            var collection = scope.collection(collectionName);
            
            var getResult = collection.get(documentKey);
            Object documentContent = getResult.contentAsObject();
            
            // Convert JsonObject to Map for proper JSON serialization
            if (documentContent instanceof com.couchbase.client.java.json.JsonObject) {
                com.couchbase.client.java.json.JsonObject jsonObj = (com.couchbase.client.java.json.JsonObject) documentContent;
                // Convert to Map to ensure proper JSON serialization
                java.util.Map<String, Object> documentMap = jsonObj.toMap();
                return documentMap;
            }
            
            // Return the document content as Object (fallback)
            return documentContent;
            
        } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
            logger.warn("Document not found with key: {}", documentKey);
            return null;
        } catch (Exception e) {
            logger.error("Failed to get document with key: {}", documentKey, e);
            throw new RuntimeException("Failed to fetch document: " + e.getMessage(), e);
        }
    }
}
