package com.couchbase.admin.fts.service;

import com.couchbase.admin.fts.model.FtsIndex;
import com.couchbase.admin.fts.model.FtsIndexDetails;
import com.couchbase.admin.fts.model.FtsIndexStats;
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
import java.util.HashMap;
import java.util.Base64;

/**
 * Service for managing FTS index information and metrics
 */
@Service
public class FtsIndexService {
    
    private static final Logger log = LoggerFactory.getLogger(FtsIndexService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Get all FTS index details for a specific bucket and scope
     */
    public List<FtsIndexDetails> getFtsIndexDetails(String connectionName, String bucketName, String scopeName) {
        try {
            // Get index definitions first
            List<FtsIndex> indexes = getFtsIndexDefinitions(connectionName, bucketName, scopeName);
            List<FtsIndexDetails> indexDetails = new ArrayList<>();
            
            // Get stats for all indexes (with error handling)
            Map<String, FtsIndexStats> statsMap = new HashMap<>();
            try {
                statsMap = getFtsIndexStats(connectionName);
                log.debug("Retrieved stats for {} indexes", statsMap.size());
            } catch (Exception statsError) {
                log.warn("Failed to get FTS index stats, continuing without stats: {}", statsError.getMessage());
                // Continue without stats - we'll use default values
            }
            
            // Combine index definitions with their stats
            for (FtsIndex index : indexes) {
                FtsIndexDetails details = new FtsIndexDetails(index.getName());
                details.setIndexDefinition(index);
                details.setBucketName(bucketName);
                
                // Find matching stats - only get basic fields needed for table
                FtsIndexStats stats = statsMap.get(index.getName());
                if (stats != null) {
                    details.setStatus(stats.getStatus());
                    details.setDocsIndexed(stats.getDocsIndexed());
                    details.setLastTimeUsed(stats.getLastTimeUsed());
                } else {
                    // Set default values if no stats available
                    log.debug("No stats found for index: {}, using defaults", index.getName());
                    details.setStatus("unknown");
                    details.setDocsIndexed(0);
                    details.setLastTimeUsed("never");
                }
                
                indexDetails.add(details);
            }
            
            log.info("Successfully retrieved {} FTS index details for bucket: {} scope: {}", 
                indexDetails.size(), bucketName, scopeName);
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
     * Get FTS index statistics from the nsstats API
     */
    public Map<String, FtsIndexStats> getFtsIndexStats(String connectionName) {
        ConnectionDetails connection = connectionService.getConnectionDetails(connectionName);
        if (connection == null) {
            log.warn("Could not get connection details for FTS stats");
            return new HashMap<>();
        }
        
        // Determine the correct port based on SSL
        int ftsPort = connection.isSslEnabled() ? 18094 : 8094;
        String protocol = connection.isSslEnabled() ? "https" : "http";
        String hostname = connectionService.getHostname(connectionName);
        
        // Construct the FTS stats API URL
        String statsUrl = String.format("%s://%s:%d/api/nsstats", protocol, hostname, ftsPort);
        
        try {
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
                statsUrl, 
                HttpMethod.GET, 
                entity, 
                Map.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
                
                // Log the response structure for debugging
                log.debug("FTS stats API response keys: {}", responseBody.keySet());
                if (log.isDebugEnabled()) {
                    responseBody.entrySet().stream().limit(3).forEach(entry -> 
                        log.debug("Sample entry - Key: {}, Value type: {}, Value: {}", 
                            entry.getKey(), 
                            entry.getValue() != null ? entry.getValue().getClass().getSimpleName() : "null",
                            entry.getValue())
                    );
                }
                
                return parseFtsIndexStats(responseBody);
            }
            
        } catch (Exception e) {
            log.error("Error fetching FTS index stats from {}: {}", statsUrl, e.getMessage());
            log.debug("Full error details", e);
        }
        
        return new HashMap<>();
    }
    
    /**
     * Parse FTS index definitions from API response
     */
    @SuppressWarnings("unchecked")
    private List<FtsIndex> parseFtsIndexDefinitions(Map<String, Object> response) {
        List<FtsIndex> indexes = new ArrayList<>();
        
        try {
            log.debug("Parsing FTS index definitions response with keys: {}", response.keySet());
            
            // The response has nested structure: response.indexDefs.indexDefs
            Map<String, Object> outerIndexDefs = (Map<String, Object>) response.get("indexDefs");
            if (outerIndexDefs == null) {
                log.warn("No 'indexDefs' found in response");
                return indexes;
            }
            
            Map<String, Object> innerIndexDefs = (Map<String, Object>) outerIndexDefs.get("indexDefs");
            if (innerIndexDefs == null) {
                log.warn("No nested 'indexDefs' found in response");
                return indexes;
            }
            
            log.debug("Found {} index definitions", innerIndexDefs.size());
            
            for (Map.Entry<String, Object> entry : innerIndexDefs.entrySet()) {
                String indexKey = entry.getKey(); // e.g., "us-core.Resources.ftsLocation"
                Object indexValue = entry.getValue();
                
                if (!(indexValue instanceof Map)) {
                    log.debug("Skipping index {} as value is not a Map but {}", indexKey, 
                        indexValue != null ? indexValue.getClass().getSimpleName() : "null");
                    continue;
                }
                
                Map<String, Object> indexData = (Map<String, Object>) indexValue;
                
                FtsIndex index = new FtsIndex();
                index.setName((String) indexData.get("name"));
                index.setUuid((String) indexData.get("uuid"));
                index.setType((String) indexData.get("type"));
                index.setParams(indexData.get("params"));
                index.setSourceType((String) indexData.get("sourceType"));
                index.setSourceName((String) indexData.get("sourceName"));
                index.setSourceUUID((String) indexData.get("sourceUUID"));
                index.setPlanParams(indexData.get("planParams"));
                
                indexes.add(index);
                log.debug("Successfully parsed index: {}", index.getName());
            }
            
            log.info("Successfully parsed {} FTS index definitions", indexes.size());
        } catch (Exception e) {
            log.error("Error parsing FTS index definitions: {}", e.getMessage(), e);
        }
        
        return indexes;
    }
    
    /**
     * Parse FTS index statistics from nsstats API response
     */
    private Map<String, FtsIndexStats> parseFtsIndexStats(Map<String, Object> response) {
        Map<String, FtsIndexStats> statsMap = new HashMap<>();
        
        try {
            log.debug("Parsing FTS stats response with {} entries", response.size());
            
            // Group stats by index name
            // Stats keys are in format: "bucket:scope.collection.indexName:metric_name"
            Map<String, Map<String, Object>> indexStatsMap = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : response.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                // Parse index name from key format: "bucket:scope.collection.indexName:metric_name"
                String indexName = extractIndexNameFromStatsKey(key);
                if (indexName != null) {
                    indexStatsMap.computeIfAbsent(indexName, k -> new HashMap<>());
                    
                    // Extract metric name (everything after the last colon)
                    int lastColonIndex = key.lastIndexOf(':');
                    if (lastColonIndex > 0 && lastColonIndex < key.length() - 1) {
                        String metricName = key.substring(lastColonIndex + 1);
                        indexStatsMap.get(indexName).put(metricName, value);
                    }
                }
            }
            
            log.debug("Grouped stats into {} indexes", indexStatsMap.size());
            
            // Create FtsIndexStats for each index
            for (Map.Entry<String, Map<String, Object>> entry : indexStatsMap.entrySet()) {
                String indexName = entry.getKey();
                Map<String, Object> statsData = entry.getValue();
                
                FtsIndexStats stats = new FtsIndexStats();
                stats.setIndexName(indexName);
                
                // Parse stats with the actual metric names from the API
                stats.setDocsIndexed(getLongValue(statsData, "doc_count", 0L));
                stats.setDiskSize(getLongValue(statsData, "num_bytes_used_disk", 0L));
                stats.setNumFilesOnDisk(getLongValue(statsData, "num_files_on_disk", 0L));
                
                // Parse query latency (in nanoseconds, convert to milliseconds)
                double avgLatencyNs = getDoubleValue(statsData, "avg_queries_latency", 0.0);
                stats.setAvgQueryLatency(avgLatencyNs / 1000000.0); // Convert ns to ms
                stats.setQueryLatency(stats.getAvgQueryLatency());
                
                // Parse last access time
                String lastAccessTime = (String) statsData.get("last_access_time");
                stats.setLastTimeUsed(lastAccessTime != null ? lastAccessTime : "never");
                
                // Set query rate and total queries (these might not be directly available)
                stats.setQueryRate(0.0); // Would need time-based calculation
                stats.setTotalQueries(0L); // Not directly available in this format
                stats.setTotalQueriesError(0L);
                stats.setTotalQueriesTimeout(0L);
                
                // Set status based on doc count
                stats.setStatus(stats.getDocsIndexed() > 0 ? "active" : "inactive");
                
                // Set additional detailed stats
                stats.setNumItemsIntroduced(stats.getDocsIndexed()); // Use doc_count as proxy
                stats.setNumItemsUpserted(0L);
                stats.setNumItemsDeleted(0L);
                stats.setNumRootMemorySegments(0L);
                stats.setNumPersistedSegments(0L);
                
                statsMap.put(indexName, stats);
                log.debug("Successfully parsed stats for index: {} with {} docs", indexName, stats.getDocsIndexed());
            }
            
            log.info("Successfully parsed {} FTS index stats", statsMap.size());
        } catch (Exception e) {
            log.error("Error parsing FTS index stats: {}", e.getMessage(), e);
        }
        
        return statsMap;
    }
    
    /**
     * Extract index name from stats key format: "bucket:scope.collection.indexName:metric_name"
     */
    private String extractIndexNameFromStatsKey(String key) {
        try {
            // Split by colons
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                // The index name is in the second part: "scope.collection.indexName"
                String scopeCollectionIndex = parts[1];
                String[] scopeParts = scopeCollectionIndex.split("\\.");
                if (scopeParts.length >= 3) {
                    // Return just the index name (last part)
                    return scopeParts[scopeParts.length - 1];
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract index name from key: {}", key);
        }
        return null;
    }
    
    /**
     * Helper method to safely get double values from the stats map
     */
    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
    
    /**
     * Helper method to safely get long values from the stats map
     */
    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
    

}