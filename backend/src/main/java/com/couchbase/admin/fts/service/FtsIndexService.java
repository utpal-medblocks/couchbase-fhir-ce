package com.couchbase.admin.fts.service;

import com.couchbase.admin.fts.model.FtsIndex;
import com.couchbase.admin.fts.model.FtsIndexDetails;
import com.couchbase.admin.fts.model.FtsProgressData;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for managing FTS index information
 */
@Service
public class FtsIndexService {
    
    private static final Logger log = LoggerFactory.getLogger(FtsIndexService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Get all FTS index details for a specific bucket and scope
     * Returns basic index information without stats (stats moved to metrics service)
     */
    public List<FtsIndexDetails> getFtsIndexDetails(String connectionName, String bucketName, String scopeName) {
        try {
            // Get index definitions
            List<FtsIndex> indexes = getFtsIndexDefinitions(connectionName, bucketName, scopeName);
            List<FtsIndexDetails> indexDetails = new ArrayList<>();
            
            // Create index details with basic information only
            for (FtsIndex index : indexes) {
                FtsIndexDetails details = new FtsIndexDetails(index.getName());
                details.setIndexDefinition(index);
                details.setBucketName(bucketName);
                
                // Set minimal default values (no stats fetching here)
                details.setStatus("available");
                details.setDocsIndexed(0);
                details.setLastTimeUsed("unknown");
                
                indexDetails.add(details);
            }
            
            // log.info("Successfully retrieved {} FTS index details for bucket: {} scope: {}", 
            //     indexDetails.size(), bucketName, scopeName);
            return indexDetails;
        } catch (Exception e) {
            log.error("Failed to get FTS index details for bucket: {} scope: {}", bucketName, scopeName, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Get FTS index definitions from the REST API using SDK's HTTP client
     */
    public List<FtsIndex> getFtsIndexDefinitions(String connectionName, String bucketName, String scopeName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                log.warn("Could not get cluster connection for FTS index definitions");
                return new ArrayList<>();
            }
            
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Make HTTP request to FTS API using SDK's HTTP client
            HttpResponse response = httpClient.get(
                HttpTarget.search(), // Use the search (FTS) target
                HttpPath.of("/api/bucket/{}/scope/{}/index", bucketName, scopeName)
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.contentAsString();
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                return parseFtsIndexDefinitions(responseMap);
            }
            
        } catch (Exception e) {
            log.error("Error fetching FTS index definitions for bucket: {} scope: {}", bucketName, scopeName, e);
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Get FTS progress data for multiple indexes using CB Console API with SDK's HTTP client
     */
    public List<FtsProgressData> getFtsProgress(String connectionName, List<String> indexNames, String bucketName, String scopeName) {
        List<FtsProgressData> progressResults = new ArrayList<>();
        
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            log.error("Could not get cluster connection for FTS progress");
            return progressResults;
        }
        
        // Use the cluster's HTTP client - this handles SSL certificates automatically
        CouchbaseHttpClient httpClient = cluster.httpClient();
        
        // Fetch progress for each index
        for (String indexName : indexNames) {
            try {
                // Build CB Console progress API URL with _p proxy
                String apiPath = String.format("/_p/fts/api/stats/index/%s.%s.%s/progress", 
                    bucketName, scopeName, indexName);
                
                HttpResponse response = httpClient.get(
                    HttpTarget.manager(), // Use manager target for console API
                    HttpPath.of(apiPath)
                );
                
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String responseBody = response.contentAsString();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> progressData = objectMapper.readValue(responseBody, Map.class);
                    FtsProgressData progress = parseProgressData(indexName, progressData);
                    progressResults.add(progress);
                    // log.debug("Successfully fetched progress for index: {}", indexName);
                } else {
                    log.error("Failed to get progress for index: {} - Status: {}", indexName, response.statusCode());
                    addErrorProgress(progressResults, indexName, "HTTP " + response.statusCode());
                }
                
            } catch (Exception e) {
                log.error("Error fetching progress for index: {} - {}", indexName, e.getMessage());
                addErrorProgress(progressResults, indexName, e.getMessage());
            }
        }
        
        //log.info("Successfully fetched progress for {}/{} FTS indexes", progressResults.size(), indexNames.size());
        return progressResults;
    }
    
    /**
     * Parse CB Console progress response into FtsProgressData
     */
    private FtsProgressData parseProgressData(String indexName, Map<String, Object> data) {
        FtsProgressData progress = new FtsProgressData(indexName);
        
        try {
            // Parse CB Console API response fields
            if (data.containsKey("doc_count")) {
                progress.setDocCount(((Number) data.get("doc_count")).longValue());
            }
            
            if (data.containsKey("tot_seq_received")) {
                progress.setTotSeqReceived(((Number) data.get("tot_seq_received")).longValue());
            }
            
            if (data.containsKey("num_mutations_to_index")) {
                progress.setNumMutationsToIndex(((Number) data.get("num_mutations_to_index")).longValue());
            }
            
            if (data.containsKey("ingest_status")) {
                progress.setIngestStatus((String) data.get("ingest_status"));
            }
            
        } catch (Exception e) {
            log.warn("Error parsing progress data for index: {} - {}", indexName, e.getMessage());
            progress.setError("Parse error: " + e.getMessage());
        }
        
        return progress;
    }
    
    /**
     * Add error progress data for failed index
     */
    private void addErrorProgress(List<FtsProgressData> results, String indexName, String error) {
        FtsProgressData errorProgress = new FtsProgressData(indexName);
        errorProgress.setError(error);
        errorProgress.setIngestStatus("error");
        results.add(errorProgress);
    }
    
    /**
     * Parse FTS index definitions from API response
     */
    private List<FtsIndex> parseFtsIndexDefinitions(Map<String, Object> response) {
        List<FtsIndex> indexes = new ArrayList<>();
        
        try {
            if (response.containsKey("indexDefs") && response.get("indexDefs") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> indexDefs = (Map<String, Object>) response.get("indexDefs");
                
                @SuppressWarnings("unchecked")
                Map<String, Object> indexesMap = (Map<String, Object>) indexDefs.get("indexDefs");
                
                if (indexesMap != null) {
                    for (Map.Entry<String, Object> entry : indexesMap.entrySet()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> indexData = (Map<String, Object>) entry.getValue();
                            FtsIndex index = parseIndexDefinition(indexData);
                            if (index != null) {
                                indexes.add(index);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse index definition for: {}", entry.getKey(), e);
                        }
                    }
                }
            }
            
            // log.info("Successfully parsed {} FTS index definitions", indexes.size());
            
        } catch (Exception e) {
            log.error("Error parsing FTS index definitions", e);
        }
        
        return indexes;
    }
    
    /**
     * Parse individual index definition
     */
    private FtsIndex parseIndexDefinition(Map<String, Object> indexData) {
        try {
            FtsIndex index = new FtsIndex();
            
            if (indexData.containsKey("name")) {
                index.setName((String) indexData.get("name"));
            }
            
            if (indexData.containsKey("uuid")) {
                index.setUuid((String) indexData.get("uuid"));
            }
            
            if (indexData.containsKey("type")) {
                index.setType((String) indexData.get("type"));
            }
            
            if (indexData.containsKey("sourceType")) {
                index.setSourceType((String) indexData.get("sourceType"));
            }
            
            if (indexData.containsKey("sourceName")) {
                index.setSourceName((String) indexData.get("sourceName"));
            }
            
            if (indexData.containsKey("sourceUUID")) {
                index.setSourceUUID((String) indexData.get("sourceUUID"));
            }
            
            if (indexData.containsKey("params")) {
                index.setParams(indexData.get("params"));
            }
            
            if (indexData.containsKey("planParams")) {
                index.setPlanParams(indexData.get("planParams"));
            }
            
            return index;
            
        } catch (Exception e) {
            log.error("Error parsing individual index definition", e);
            return null;
        }
    }
}