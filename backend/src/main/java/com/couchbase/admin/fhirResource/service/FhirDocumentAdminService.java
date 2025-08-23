package com.couchbase.admin.fhirResource.service;

import com.couchbase.admin.fhirResource.model.DocumentKeyRequest;
import com.couchbase.admin.fhirResource.model.DocumentKeyResponse;
import com.couchbase.admin.fhirResource.model.DocumentMetadata;
import com.couchbase.admin.fhirResource.model.DocumentMetadataResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class FhirDocumentAdminService {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirDocumentAdminService.class);
    
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
            // Log this query
            logger.debug("Executing SQL query: {}", sql);
            
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
     * Get document metadata using FTS search for a specific FHIR collection with pagination
     */
    public DocumentMetadataResponse getDocumentMetadata(DocumentKeyRequest request, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            // Calculate offset for pagination
            int offset = request.getPage() * request.getPageSize();
            
            // Build the FTS query
            String ftsQuery = buildFtsMetadataQuery(request, offset);
            
            logger.debug("Executing FTS query: {}", ftsQuery);
            
            // Execute query with timeout
            var result = cluster.query(ftsQuery, QueryOptions.queryOptions()
                .timeout(Duration.ofSeconds(30)));
            
            // Extract document metadata from result
            List<DocumentMetadata> documents = new ArrayList<>();
            for (var row : result.rowsAsObject()) {
                DocumentMetadata metadata = parseDocumentMetadata(row);
                if (metadata != null) {
                    documents.add(metadata);
                }
            }
            
            // Get total count (separate query for accurate pagination)
            int totalCount = getTotalFtsDocumentCount(cluster, request);
            
            // Calculate if there are more pages
            boolean hasMore = (offset + request.getPageSize()) < totalCount;
            
            return new DocumentMetadataResponse(
                request.getBucketName(),
                request.getCollectionName(),
                documents,
                totalCount,
                request.getPage(),
                request.getPageSize(),
                hasMore
            );
            
        } catch (Exception e) {
            logger.error("Failed to get document metadata for bucket: {}, collection: {}", 
                        request.getBucketName(), request.getCollectionName(), e);
            throw new RuntimeException("Failed to fetch document metadata: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build the FTS query for fetching document metadata
     */
    private String buildFtsMetadataQuery(DocumentKeyRequest request, int offset) {
        StringBuilder query = new StringBuilder();
        
        // Base SELECT with FTS search
        query.append("SELECT ")
             .append("resource.id, ")
             .append("resource.meta.versionId, ")
             .append("resource.meta.lastUpdated, ")
             .append("resource.meta.tag[0].code, ")
             .append("resource.meta.tag[0].display ");
        
        query.append("FROM `")
             .append(request.getBucketName())
             .append("`.`Resources`.`")
             .append(request.getCollectionName())
             .append("` resource ");
        
        // Add FTS search condition (without pagination in FTS - we'll use LIMIT/OFFSET)
        query.append("WHERE SEARCH(resource, { \"match_all\": {} }, ")
             .append("{ \"index\": \"")
             .append(request.getBucketName())
             .append(".Resources.fts")
             .append(request.getCollectionName())
             .append("\" })");
        
        // Add patient filtering if specified
        if (request.getPatientId() != null && !request.getPatientId().trim().isEmpty()) {
            if ("Patient".equals(request.getCollectionName())) {
                // For Patient collection, filter by the id field
                query.append(" AND resource.id LIKE '")
                     .append(request.getPatientId())
                     .append("%'");
            } else {
                // For other collections, filter by patient reference
                query.append(" AND ANY ref IN OBJECT_PAIRS(resource) SATISFIES ")
                     .append("ref.`val`.reference LIKE 'Patient/")
                     .append(request.getPatientId())
                     .append("' END");
            }
        }
        
        // Add LIMIT and OFFSET for N1QL pagination (no sorting needed for UUIDs)
        query.append(" LIMIT ").append(request.getPageSize())
             .append(" OFFSET ").append(offset);
        
        return query.toString();
    }
    
    /**
     * Parse document metadata from query result row
     */
    private DocumentMetadata parseDocumentMetadata(JsonObject row) {
        return parseDocumentMetadata(row, true); // Default to current version
    }
    
    /**
     * Parse document metadata from query result row with version flag
     */
    private DocumentMetadata parseDocumentMetadata(JsonObject row, boolean isCurrentVersion) {
        try {
            String id = row.getString("id");
            String versionId = row.getString("versionId");
            String lastUpdated = row.getString("lastUpdated");
            String code = row.getString("code");
            String display = row.getString("display");
            
            return new DocumentMetadata(id, versionId, lastUpdated, code, display, isCurrentVersion);
            
        } catch (Exception e) {
            logger.warn("Failed to parse document metadata from row: {}, error: {}", row, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get total count of documents using FTS for pagination
     */
    private int getTotalFtsDocumentCount(Cluster cluster, DocumentKeyRequest request) {
        try {
            StringBuilder countQuery = new StringBuilder();
            
            countQuery.append("SELECT COUNT(*) as count FROM `")
                     .append(request.getBucketName())
                     .append("`.`Resources`.`")
                     .append(request.getCollectionName())
                     .append("` resource ");
            
            // Add FTS search condition for count
            countQuery.append("WHERE SEARCH(resource, { \"match_all\": {} }, ")
                     .append("{ \"index\": \"")
                     .append(request.getBucketName())
                     .append(".Resources.fts")
                     .append(request.getCollectionName())
                     .append("\" })");
            
            // Add same patient filtering as main query
            if (request.getPatientId() != null && !request.getPatientId().trim().isEmpty()) {
                if ("Patient".equals(request.getCollectionName())) {
                    countQuery.append(" AND resource.id LIKE '")
                              .append(request.getPatientId())
                              .append("%'");
                } else {
                    countQuery.append(" AND ANY ref IN OBJECT_PAIRS(resource) SATISFIES ")
                              .append("ref.`val`.reference LIKE 'Patient/")
                              .append(request.getPatientId())
                              .append("' END");
                }
            }
            
            var result = cluster.query(countQuery.toString(), QueryOptions.queryOptions()
                .timeout(Duration.ofSeconds(30)));
            
            if (result.rowsAsObject().size() > 0) {
                return result.rowsAsObject().get(0).getInt("count");
            }
            
            return 0;
            
        } catch (Exception e) {
            logger.warn("Failed to get FTS total count, returning 0: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get version history for a specific document ID
     */
    public List<DocumentMetadata> getVersionHistory(String bucketName, String documentId, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            // Build the FTS query for versions
            String versionsQuery = buildVersionsQuery(bucketName, documentId);
            
            logger.debug("Executing versions query: {}", versionsQuery);
            
            // Execute query with timeout
            var result = cluster.query(versionsQuery, QueryOptions.queryOptions()
                .timeout(Duration.ofSeconds(30)));
            
            // Extract version metadata from result (these are historical versions)
            List<DocumentMetadata> versions = new ArrayList<>();
            for (var row : result.rowsAsObject()) {
                DocumentMetadata metadata = parseDocumentMetadata(row, false); // Historical versions
                if (metadata != null) {
                    versions.add(metadata);
                }
            }
            
            // Sort by versionId (ascending - oldest first)
            versions.sort((a, b) -> Integer.compare(
                Integer.parseInt(a.getVersionId()), 
                Integer.parseInt(b.getVersionId())
            ));
            
            return versions;
            
        } catch (Exception e) {
            logger.error("Failed to get version history for document: {}", documentId, e);
            throw new RuntimeException("Failed to fetch version history: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build the FTS query for fetching version history from Versions collection
     */
    private String buildVersionsQuery(String bucketName, String documentId) {
        StringBuilder query = new StringBuilder();
        
        // Base SELECT with FTS search on Versions collection
        query.append("SELECT ")
             .append("resource.id, ")
             .append("resource.meta.versionId, ")
             .append("resource.meta.lastUpdated, ")
             .append("resource.meta.tag[0].code, ")
             .append("resource.meta.tag[0].display ");
        
        query.append("FROM `")
             .append(bucketName)
             .append("`.`Resources`.`Versions` resource ");
        
        // Add FTS search condition to find all versions of this document using match
        query.append("WHERE SEARCH(resource, { ")
             .append("\"match\": \"")
             .append(documentId)
             .append("\", ")
             .append("\"field\": \"id\" ")
             .append("}, { \"index\": \"")
             .append(bucketName)
             .append(".Resources.ftsVersions\" })");
        
        return query.toString();
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
