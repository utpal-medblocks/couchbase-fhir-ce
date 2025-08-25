package com.couchbase.admin.fts.service;

import com.couchbase.admin.fts.model.FtsIndex;
import com.couchbase.admin.fts.model.FtsIndexDetails;
import com.couchbase.admin.fts.model.FtsProgressData;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.connections.service.ConnectionService.ConnectionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/**
 * Service for managing FTS index information
 */
@Service
public class FtsIndexService {
    
    private static final Logger log = LoggerFactory.getLogger(FtsIndexService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
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
     * Get FTS index definitions from the REST API
     */
    public List<FtsIndex> getFtsIndexDefinitions(String connectionName, String bucketName, String scopeName) {
        try {
            ConnectionDetails connection = connectionService.getConnectionDetails(connectionName);
            if (connection == null) {
                log.warn("Could not get connection details for FTS index definitions");
                return new ArrayList<>();
            }
            
            // Determine the correct port based on SSL
            int ftsPort = connection.isSslEnabled() ? 18094 : 8094;
            String protocol = connection.isSslEnabled() ? "https" : "http";
            String hostname = connectionService.getHostname(connectionName);
            
            // Construct the FTS API URL for getting index definitions
            String ftsUrl = String.format("%s://%s:%d/api/bucket/%s/scope/%s/index", 
                protocol, hostname, ftsPort, bucketName, scopeName);
            
            // Make HTTP request
            RestTemplate restTemplate = new RestTemplate();
            
            // Set up basic authentication
            String auth = connection.getUsername() + ":" + connection.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + encodedAuth);
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<?> response = restTemplate.exchange(
                ftsUrl, 
                HttpMethod.GET, 
                entity, 
                Map.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
                return parseFtsIndexDefinitions(responseBody);
            }
            
        } catch (Exception e) {
            log.error("Error fetching FTS index definitions for bucket: {} scope: {}", bucketName, scopeName, e);
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Get FTS progress data for multiple indexes using CB Console API
     */
    public List<FtsProgressData> getFtsProgress(String connectionName, List<String> indexNames, String bucketName, String scopeName) {
        List<FtsProgressData> progressResults = new ArrayList<>();
        
        ConnectionDetails connection = connectionService.getConnectionDetails(connectionName);
        if (connection == null) {
            log.error("Could not get connection details for FTS progress");
            return progressResults;
        }
        
        // Determine the correct port for CB Console (8091/18091 with _p proxy)
        int consolePort = connection.isSslEnabled() ? 18091 : 8091;
        String protocol = connection.isSslEnabled() ? "https" : "http";
        String hostname = connectionService.getHostname(connectionName);
        
        RestTemplate restTemplate = new RestTemplate();
        
        // Set up basic authentication
        String auth = connection.getUsername() + ":" + connection.getPassword();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        // Fetch progress for each index
        for (String indexName : indexNames) {
            try {
                // Build CB Console progress API URL with _p proxy
                String progressUrl = String.format("%s://%s:%d/_p/fts/api/stats/index/%s.%s.%s/progress", 
                    protocol, hostname, consolePort, bucketName, scopeName, indexName);
                
                // log.debug("Fetching FTS progress from: {}", progressUrl);
                
                ResponseEntity<Map> response = restTemplate.exchange(
                    progressUrl, 
                    HttpMethod.GET, 
                    entity, 
                    Map.class
                );
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> progressData = response.getBody();
                    FtsProgressData progress = parseProgressData(indexName, progressData);
                    progressResults.add(progress);
                    // log.debug("Successfully fetched progress for index: {}", indexName);
                } else {
                    log.error("Failed to get progress for index: {} - Status: {}", indexName, response.getStatusCode());
                    addErrorProgress(progressResults, indexName, "HTTP " + response.getStatusCode());
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