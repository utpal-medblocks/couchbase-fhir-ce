package com.couchbase.admin.fhirResource.service;

import com.couchbase.admin.fhirResource.model.DocumentKeyRequest;
import com.couchbase.admin.fhirResource.model.DocumentKeyResponse;
import com.couchbase.admin.fhirResource.model.DocumentMetadata;
import com.couchbase.admin.fhirResource.model.DocumentMetadataResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.java.search.result.SearchRow;
import com.couchbase.client.java.search.sort.SearchSort;
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
     * Get document keys using FTS SDK for a specific FHIR collection with pagination
     * Returns only document keys (no full document fetches)
     * Uses direct FTS search with sorting, patient filtering, and optimized counts
     */
    public DocumentMetadataResponse getDocumentMetadata(DocumentKeyRequest request, String connectionName) {
        logger.info("📥 getDocumentMetadata called: bucket={}, collection={}, page={}, pageSize={}, patientId={}, resourceType={}", 
                   request.getBucketName(), request.getCollectionName(), request.getPage(), 
                   request.getPageSize(), request.getPatientId(), request.getResourceType());
        
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                logger.error("❌ No active Couchbase connection found for: {}", connectionName);
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            logger.debug("✅ Got cluster connection: {}", connectionName);
            
            // Calculate offset for pagination
            int offset = request.getPage() * request.getPageSize();
            
            // Build FTS index name
            String ftsIndex = buildFtsIndexName(request.getBucketName(), request.getCollectionName());
            logger.info("🔍 FTS Index: {}", ftsIndex);
            
            // Build FTS search query with patient filter
            SearchQuery searchQuery = buildFtsSearchQuery(request);
            logger.info("🔍 FTS Query built: {}", searchQuery.export().toString());
            
            // Build search options with sorting by meta.lastUpdated descending
            SearchOptions searchOptions = SearchOptions.searchOptions()
                .timeout(Duration.ofSeconds(30))
                .limit(request.getPageSize())
                .skip(offset)
                .sort(SearchSort.byField("meta.lastUpdated").desc(true))
                .includeLocations(false)
                .disableScoring(true);
            
            logger.info("🔍 Executing FTS SDK search: index={}, limit={}, skip={}", ftsIndex, request.getPageSize(), offset);
            
            long startTime = System.currentTimeMillis();
            
            // Execute FTS search to get document keys
            SearchResult searchResult = cluster.searchQuery(ftsIndex, searchQuery, searchOptions);
            
            // Check for FTS errors
            if (searchResult.metaData().errors() != null && !searchResult.metaData().errors().isEmpty()) {
                String errorMsg = searchResult.metaData().errors().toString();
                logger.error("❌ FTS search returned errors: {}", errorMsg);
                throw new RuntimeException("FTS search failed: " + errorMsg);
            }
            
            long ftsTime = System.currentTimeMillis() - startTime;
            
            // Extract document keys only (no KV fetches!)
            List<DocumentMetadata> documents = new ArrayList<>();
            for (SearchRow row : searchResult.rows()) {
                String documentKey = row.id();
                
                // Extract just the ID part from the document key (e.g., "Patient/123" -> "123")
                String id = documentKey;
                int slashIndex = documentKey.indexOf('/');
                if (slashIndex > 0 && slashIndex < documentKey.length() - 1) {
                    id = documentKey.substring(slashIndex + 1);
                }
                
                // Create minimal metadata with just the ID
                // Other fields can be null/empty since we only display keys in the table
                DocumentMetadata metadata = new DocumentMetadata(
                    id,           // id = just the ID part (without resourceType prefix)
                    null,         // versionId - not needed for key-only display
                    null,         // lastUpdated - not needed for key-only display
                    null,         // code - not needed for key-only display
                    null,         // display - not needed for key-only display
                    true          // isCurrentVersion
                );
                documents.add(metadata);
            }
            
            // Get total count using optimized FTS count query
            int totalCount = getFtsCount(cluster, ftsIndex, searchQuery);
            
            logger.info("✅ FTS key fetch COMPLETE: {} keys in {} ms, total count: {}", 
                       documents.size(), ftsTime, totalCount);
            
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
            logger.error("Failed to get document keys for bucket: {}, collection: {}", 
                        request.getBucketName(), request.getCollectionName(), e);
            throw new RuntimeException("Failed to fetch document keys: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build FTS index name based on bucket and collection
     */
    private String buildFtsIndexName(String bucketName, String collectionName) {
        return bucketName + ".Resources.fts" + collectionName;
    }
    
    /**
     * Build FTS search query with resourceType and patient filtering
     */
    private SearchQuery buildFtsSearchQuery(DocumentKeyRequest request) {
        List<SearchQuery> queries = new ArrayList<>();
        
        // Add resourceType filter for General collection
        if ("General".equals(request.getCollectionName()) && 
            request.getResourceType() != null && !request.getResourceType().trim().isEmpty()) {
            logger.debug("🔍 Adding resourceType filter: {}", request.getResourceType());
            queries.add(SearchQuery.match(request.getResourceType()).field("resourceType"));
        }
        
        // Add patient filtering if specified
        if (request.getPatientId() != null && !request.getPatientId().trim().isEmpty()) {
            if ("Patient".equals(request.getCollectionName())) {
                // For Patient collection, use exact keyword match on the id field
                String patientId = request.getPatientId().trim();
                logger.info("🔍 Adding Patient filter on id field: {}", patientId);
                queries.add(SearchQuery.match(patientId).field("id"));
            } else {
                // For other collections, use disjuncts to search both patient.reference and subject.reference
                // Use exact keyword match for "Patient/abc" patterns
                String patientReference = "Patient/" + request.getPatientId().trim();
                logger.info("🔍 Adding Patient filter on patient/subject.reference: {}", patientReference);
                queries.add(SearchQuery.disjuncts(
                    SearchQuery.match(patientReference).field("patient.reference"),
                    SearchQuery.match(patientReference).field("subject.reference")
                ));
            }
        } else {
            logger.debug("🔍 No patient filter specified");
        }
        
        // Combine queries or use match_all
        if (queries.isEmpty()) {
            return SearchQuery.matchAll();
        } else if (queries.size() == 1) {
            return queries.get(0);
        } else {
            return SearchQuery.conjuncts(queries.toArray(new SearchQuery[0]));
        }
    }
    
    /**
     * Get count using optimized FTS query with limit(0)
     */
    private int getFtsCount(Cluster cluster, String ftsIndex, SearchQuery searchQuery) {
        try {
            SearchOptions countOptions = SearchOptions.searchOptions()
                .timeout(Duration.ofSeconds(30))
                .limit(0)  // Don't fetch any documents, just get the count
                .includeLocations(false)
                .disableScoring(true);
            
            SearchResult searchResult = cluster.searchQuery(ftsIndex, searchQuery, countOptions);
            
            // Check for errors
            if (searchResult.metaData().errors() != null && !searchResult.metaData().errors().isEmpty()) {
                logger.warn("FTS count query returned errors: {}", searchResult.metaData().errors());
                return 0;
            }
            
            return (int) searchResult.metaData().metrics().totalRows();
            
        } catch (Exception e) {
            logger.warn("Failed to get FTS count, returning 0: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Parse document metadata from query result row (used by version history)
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
     * Uses ARRAY comprehension to filter tags by system to find the Couchbase FHIR custom tag
     */
    private String buildVersionsQuery(String bucketName, String documentId) {
        StringBuilder query = new StringBuilder();
        
        // Base SELECT with FTS search on Versions collection
        // Use ARRAY comprehension to filter tags by system (escape 'system' as it's a reserved word)
        query.append("SELECT ")
             .append("resource.id, ")
             .append("resource.meta.versionId, ")
             .append("resource.meta.lastUpdated, ")
             // Filter tags array to find the one with our custom system
             // Note: `system` must be escaped as it's a reserved word in N1QL
             .append("(ARRAY tag.code FOR tag IN resource.meta.tag WHEN tag.`system` = 'http://couchbase.fhir.com/fhir/custom-tags' END)[0] AS code, ")
             .append("(ARRAY tag.display FOR tag IN resource.meta.tag WHEN tag.`system` = 'http://couchbase.fhir.com/fhir/custom-tags' END)[0] AS display ");
        
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
     * Get a specific document by its key using CB Server REST API
     */
    public Object getDocument(String bucketName, String collectionName, String documentKey, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new IllegalStateException("No active Couchbase connection found for: " + connectionName);
            }
            
            // Use SDK's HTTP client for REST API call (handles certificates automatically)
            com.couchbase.client.java.http.CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Construct the REST path for the document
            // Format: /pools/default/buckets/{bucket}/scopes/{scope}/collections/{collection}/docs/{docId}
            // URL encode the document key to handle special characters like slashes
            String encodedDocumentKey = java.net.URLEncoder.encode(documentKey, java.nio.charset.StandardCharsets.UTF_8);
            String restPath = String.format("/pools/default/buckets/%s/scopes/Resources/collections/%s/docs/%s",
                bucketName, collectionName, encodedDocumentKey);
            
            logger.debug("🔍 Fetching document via REST API: {}", restPath);
            
            // Make the REST API call
            com.couchbase.client.java.http.HttpResponse httpResponse = httpClient.get(
                com.couchbase.client.java.http.HttpTarget.manager(),
                com.couchbase.client.java.http.HttpPath.of(restPath)
            );
            
            // Check if request was successful
            if (httpResponse.statusCode() == 200) {
                String responseBody = new String(httpResponse.content());
                logger.debug("✅ Document retrieved successfully via REST API");
                
                // Parse JSON response to extract just the document content
                com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode fullResponse = objectMapper.readTree(responseBody);
                
                // Extract the "json" field which contains the actual FHIR document as a string
                if (fullResponse.has("json")) {
                    String documentJsonString = fullResponse.get("json").asText();
                    // Parse the JSON string to return the actual FHIR document object
                    return objectMapper.readValue(documentJsonString, Object.class);
                } else {
                    // Fallback: return the full response if "json" field is not found
                    logger.warn("Response does not contain 'json' field, returning full response");
                    return objectMapper.readValue(responseBody, Object.class);
                }
                
            } else if (httpResponse.statusCode() == 404) {
                logger.warn("Document not found with key: {}", documentKey);
                return null;
                
            } else {
                String errorBody = new String(httpResponse.content());
                logger.error("REST API call failed with status {}: {}", httpResponse.statusCode(), errorBody);
                throw new RuntimeException("Failed to fetch document via REST API: HTTP " + httpResponse.statusCode());
            }
            
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("Failed to parse JSON response for document key: {}", documentKey, e);
            throw new RuntimeException("Failed to parse document JSON: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Failed to get document with key: {}", documentKey, e);
            throw new RuntimeException("Failed to fetch document: " + e.getMessage(), e);
        }
    }
}
