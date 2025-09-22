package com.couchbase.admin.buckets.service;

import com.couchbase.admin.buckets.model.BucketDetails;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.client.java.http.HttpPostOptions;
import com.couchbase.client.java.http.HttpBody;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

/**
 * Service for managing bucket information and metrics
 * Focuses on FHIR-enabled buckets only
 */
@Service
public class BucketsService {
    
    private static final Logger log = LoggerFactory.getLogger(BucketsService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Get all FHIR-enabled bucket names
     */
    public List<String> getFhirBucketNames(String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return new ArrayList<>();
            }
            
            // Query all buckets and check which ones are FHIR-enabled
            String sql = "SELECT name FROM system:buckets WHERE namespace_id = 'default'";
            var result = cluster.query(sql);
            var buckets = new ArrayList<String>();
            
            for (var row : result.rowsAsObject()) {
                String bucketName = row.getString("name");
                if (isFhirBucket(bucketName, connectionName)) {
                    buckets.add(bucketName);
                }
            }
            
            return buckets;
        } catch (Exception e) {
            log.error("Failed to get FHIR bucket names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Get detailed metrics for all FHIR-enabled buckets
     */
    public List<BucketDetails> getFhirBucketDetails(String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                return new ArrayList<>();
            }
            
            // First get FHIR bucket names (reuse existing logic)
            List<String> fhirBucketNames = getFhirBucketNames(connectionName);
            List<BucketDetails> bucketDetails = new ArrayList<>();
            
            for (String bucketName : fhirBucketNames) {
                // Fetch detailed metrics for this FHIR bucket
                BucketDetails details = getBucketMetrics(bucketName, connectionName);
                if (details != null) {
                    bucketDetails.add(details);
                }
            }
            
            return bucketDetails;
        } catch (Exception e) {
            log.error("Failed to get FHIR bucket details: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Get detailed metrics for a specific bucket using Couchbase SDK's HTTP client
     */
    private BucketDetails getBucketMetrics(String bucketName, String connectionName) {
        try {
            // Get the cluster connection
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                log.warn("Could not get cluster connection for bucket metrics");
                return null;
            }
            
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Make HTTP request to get bucket stats using SDK's HTTP client
            HttpResponse response = httpClient.get(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/buckets/{}", bucketName)
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.contentAsString();
                @SuppressWarnings("unchecked")
                Map<String, Object> bucketStatsMap = objectMapper.readValue(responseBody, Map.class);
                BucketDetails details = parseBucketStats(bucketName, bucketStatsMap);
                
                // Fetch collection metrics for this bucket
                Map<String, Map<String, Object>> collectionMetrics = fetchCollectionMetrics(cluster, bucketName);
                details.setCollectionMetrics(collectionMetrics);
                
                return details;
            }
            
        } catch (Exception e) {
            log.error("Error fetching bucket metrics for bucket: {} on connection: {}", bucketName, connectionName, e);
        }
        
        return null;
    }
    
    private BucketDetails parseBucketStats(String bucketName, Map<String, Object> bucketStats) {
        BucketDetails details = new BucketDetails(bucketName);
        
        // Parse bucket type (membase = couchbase)
        String bucketType = (String) bucketStats.get("bucketType");
        details.setBucketType("membase".equals(bucketType) ? "couchbase" : bucketType);
        
        details.setStorageBackend((String) bucketStats.get("storageBackend"));
        details.setEvictionPolicy((String) bucketStats.get("evictionPolicy"));
        details.setDurabilityMinLevel((String) bucketStats.get("durabilityMinLevel"));
        details.setConflictResolutionType((String) bucketStats.get("conflictResolutionType"));
        
        // Parse replica number
        Object replicaNumber = bucketStats.get("replicaNumber");
        details.setReplicaNumber(replicaNumber != null ? ((Number) replicaNumber).intValue() : 0);
        
        // Parse maxTTL
        Object maxTTL = bucketStats.get("maxTTL");
        details.setMaxTTL(maxTTL != null ? ((Number) maxTTL).longValue() : 0L);
        
        // Parse quota information
        @SuppressWarnings("unchecked")
        Map<String, Object> quota = (Map<String, Object>) bucketStats.get("quota");
        if (quota != null) {
            Object ram = quota.get("ram");
            details.setRam(ram != null ? ((Number) ram).longValue() : 0L);
        }
        
        // Parse basic stats
        @SuppressWarnings("unchecked")
        Map<String, Object> basicStats = (Map<String, Object>) bucketStats.get("basicStats");
        if (basicStats != null) {
            Object quotaPercentUsed = basicStats.get("quotaPercentUsed");
            details.setQuotaPercentUsed(quotaPercentUsed != null ? ((Number) quotaPercentUsed).doubleValue() : 0.0);
            
            Object opsPerSec = basicStats.get("opsPerSec");
            details.setOpsPerSec(opsPerSec != null ? ((Number) opsPerSec).doubleValue() : 0.0);
            
            Object itemCount = basicStats.get("itemCount");
            details.setItemCount(itemCount != null ? ((Number) itemCount).longValue() : 0L);
            
            Object diskUsed = basicStats.get("diskUsed");
            details.setDiskUsed(diskUsed != null ? ((Number) diskUsed).longValue() : 0L);
            
            // Calculate resident ratio if we have item count
            long itemCountValue = details.getItemCount();
            if (itemCountValue > 0) {
                Object vbActiveNumNonResident = basicStats.get("vbActiveNumNonResident");
                if (vbActiveNumNonResident != null) {
                    long nonResident = ((Number) vbActiveNumNonResident).longValue();
                    details.setResidentRatio(100.0 - ((double) nonResident / itemCountValue) * 100.0);
                } else {
                    details.setResidentRatio(100.0);
                }
            } else {
                details.setResidentRatio(100.0);
            }
        }
        
        // Set default cache hit to 100%
        details.setCacheHit(100.0);
        
        return details;
    }
    
    private Map<String, Map<String, Object>> fetchCollectionMetrics(Cluster cluster, String bucketName) {
        Map<String, Map<String, Object>> collectionMetrics = new HashMap<>();
        
        try {
            // First, get scopes and collections for the bucket
            Map<String, List<String>> scopeCollections = fetchScopesAndCollections(cluster, bucketName);
            
            // For each scope, fetch collection metrics
            for (Map.Entry<String, List<String>> entry : scopeCollections.entrySet()) {
                String scopeName = entry.getKey();
                List<String> collections = entry.getValue();
                
                // Fetch metrics for all collections in this scope
                Map<String, Object> scopeMetrics = fetchCollectionMetricsForScope(cluster, bucketName, scopeName, collections);
                collectionMetrics.put(scopeName, scopeMetrics);
            }
            
        } catch (Exception e) {
            log.error("Error fetching collection metrics for bucket: {}", bucketName, e);
        }
        
        return collectionMetrics;
    }
    
    private Map<String, List<String>> fetchScopesAndCollections(Cluster cluster, String bucketName) {
        Map<String, List<String>> scopeCollections = new HashMap<>();
        
        try {
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Make HTTP request to get scopes using SDK's HTTP client
            HttpResponse response = httpClient.get(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/buckets/{}/scopes", bucketName)
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.contentAsString();
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> scopes = (List<Map<String, Object>>) responseMap.get("scopes");
                
                if (scopes != null) {
                    for (Map<String, Object> scope : scopes) {
                        String scopeName = (String) scope.get("name");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> collections = (List<Map<String, Object>>) scope.get("collections");
                        
                        List<String> collectionNames = new ArrayList<>();
                        if (collections != null) {
                            for (Map<String, Object> collection : collections) {
                                collectionNames.add((String) collection.get("name"));
                            }
                        }
                        
                        scopeCollections.put(scopeName, collectionNames);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error fetching scopes and collections for bucket: {}", bucketName, e);
        }
        
        return scopeCollections;
    }
    
    private Map<String, Object> fetchCollectionMetricsForScope(Cluster cluster, String bucketName, 
                                                              String scopeName, List<String> collections) {
        Map<String, Object> scopeMetrics = new HashMap<>();
        
        try {
            // The metrics we want to fetch (same as your Electron app)
            List<String> metrics = Arrays.asList(
                "kv_collection_item_count",
                "kv_collection_mem_used_bytes", 
                "kv_collection_data_size_bytes",
                "kv_collection_ops"
            );
            
            // Call the Couchbase stats API to get collection metrics
            List<Map<String, Object>> apiResponse = fetchCollectionStatsFromAPI(cluster, bucketName, scopeName, metrics);
            
            // Parse the response and map to collection data
            Map<String, Object> collectionsData = parseCollectionMetricsResponse(apiResponse, collections);
            scopeMetrics.put("collections", collectionsData);
            
        } catch (Exception e) {
            log.error("Error fetching collection metrics for scope: {} in bucket: {}", scopeName, bucketName, e);
            
            // Fallback to basic collection info if metrics fetch fails
            Map<String, Object> collectionsData = new HashMap<>();
            for (String collectionName : collections) {
                Map<String, Object> collectionMetrics = new HashMap<>();
                collectionMetrics.put("items", 0L);
                collectionMetrics.put("diskSize", 0L);
                collectionMetrics.put("memUsed", 0L);
                collectionMetrics.put("ops", 0.0);
                collectionsData.put(collectionName, collectionMetrics);
            }
            scopeMetrics.put("collections", collectionsData);
        }
        
        return scopeMetrics;
    }
    
    private List<Map<String, Object>> fetchCollectionStatsFromAPI(Cluster cluster, String bucketName, 
                                                                 String scopeName, List<String> metrics) {
        try {
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Construct the data payload with multiple metrics (same structure as your Electron app)
            List<Map<String, Object>> requestData = new ArrayList<>();
            
            for (String metricName : metrics) {
                Map<String, Object> metricRequest = new HashMap<>();
                metricRequest.put("start", -1);
                metricRequest.put("nodesAggregation", "sum");
                
                // Build metric labels
                List<Map<String, String>> metricLabels = new ArrayList<>();
                metricLabels.add(Map.of("label", "name", "value", metricName));
                metricLabels.add(Map.of("label", "bucket", "value", bucketName));
                metricLabels.add(Map.of("label", "scope", "value", scopeName));
                metricRequest.put("metric", metricLabels);
                
                // Add applyFunctions for ops metric (same as your Electron app)
                if ("kv_collection_ops".equals(metricName)) {
                    metricRequest.put("applyFunctions", Arrays.asList("irate"));
                }
                
                requestData.add(metricRequest);
            }
            
            // Make the API call using SDK's HTTP client
            String requestBody = objectMapper.writeValueAsString(requestData);
            HttpResponse response = httpClient.post(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/stats/range"),
                HttpPostOptions.httpPostOptions()
                    .body(HttpBody.json(requestBody))
                    .header("Content-Type", "application/json")
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.contentAsString();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> responseList = objectMapper.readValue(responseBody, List.class);
                return responseList;
            }
            
        } catch (Exception e) {
            log.error("Error calling collection stats API for scope: {} in bucket: {}", scopeName, bucketName, e);
        }
        
        return new ArrayList<>();
    }
    
    private Map<String, Object> parseCollectionMetricsResponse(List<Map<String, Object>> apiResponse, List<String> collections) {
        Map<String, Object> collectionsData = new HashMap<>();
        
        // Initialize all collections with default values
        for (String collectionName : collections) {
            Map<String, Object> collectionMetrics = new HashMap<>();
            collectionMetrics.put("items", 0L);
            collectionMetrics.put("diskSize", 0L);
            collectionMetrics.put("memUsed", 0L);
            collectionMetrics.put("ops", 0.0);
            collectionsData.put(collectionName, collectionMetrics);
        }
        
        // Mapping from collection name to its metrics (same logic as your parseAndUpdateCollections)
        Map<String, Map<String, Object>> metricsMapping = new HashMap<>();
        
        try {
            for (Map<String, Object> metricType : apiResponse) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) metricType.get("data");
                
                if (data != null) {
                    for (Map<String, Object> metricData : data) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metric = (Map<String, Object>) metricData.get("metric");
                        @SuppressWarnings("unchecked")
                        List<List<Object>> values = (List<List<Object>>) metricData.get("values");
                        
                        if (metric != null && values != null && !values.isEmpty()) {
                            String collectionName = (String) metric.get("collection");
                            String metricName = (String) metric.get("name");
                            String metricValue = (String) values.get(0).get(1); // First value, second element
                            
                            if (collectionName != null && metricName != null && metricValue != null) {
                                metricsMapping.computeIfAbsent(collectionName, k -> new HashMap<>());
                                
                                // Map the metric value to the right field (same mapping as your Electron app)
                                switch (metricName) {
                                    case "kv_collection_item_count":
                                        metricsMapping.get(collectionName).put("items", Long.parseLong(metricValue));
                                        break;
                                    case "kv_collection_data_size_bytes":
                                        metricsMapping.get(collectionName).put("diskSize", Long.parseLong(metricValue));
                                        break;
                                    case "kv_collection_mem_used_bytes":
                                        metricsMapping.get(collectionName).put("memUsed", Long.parseLong(metricValue));
                                        break;
                                    case "kv_collection_ops":
                                        metricsMapping.get(collectionName).put("ops", Double.parseDouble(metricValue));
                                        break;
                                }
                            }
                        }
                    }
                }
            }
            
            // Update collections data with actual metrics
            for (Map.Entry<String, Map<String, Object>> entry : metricsMapping.entrySet()) {
                String collectionName = entry.getKey();
                Map<String, Object> metrics = entry.getValue();
                
                if (collectionsData.containsKey(collectionName)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> collectionData = (Map<String, Object>) collectionsData.get(collectionName);
                    collectionData.putAll(metrics);
                }
            }
            
        } catch (Exception e) {
            log.error("Error parsing collection metrics response", e);
        }
        
        return collectionsData;
    }
    
    
    /**
     * Check if a bucket is FHIR-enabled by looking for the configuration document
     * Uses REST API to avoid SDK retry issues
     */
    public boolean isFhirBucket(String bucketName, String connectionName) {
        try {
            // Get connection details from connection service
            String hostname = connectionService.getHostname(connectionName);
            int port = connectionService.getPort(connectionName);
            var connectionDetails = connectionService.getConnectionDetails(connectionName);
            
            if (hostname == null || connectionDetails == null) {
                log.warn("Could not get connection details for REST call");
                return false;
            }
            
            // Use REST API to check if fhir-config document exists
            return checkFhirConfigViaRest(hostname, port, bucketName, connectionName,
                                        connectionDetails.getUsername(), 
                                        connectionDetails.getPassword());
            
        } catch (Exception e) {
            log.warn("Failed to check if bucket {} is FHIR-enabled: {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check FHIR config document via REST API using SDK's HTTP client
     */
    private boolean checkFhirConfigViaRest(String hostname, int port, String bucketName, String connectionName,
                                         String username, String password) {
        try {
            // Get the cluster connection to access the HTTP client
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                log.warn("No cluster connection available for REST call");
                return false;
            }
            
            // Use SDK's HTTP client
            com.couchbase.client.java.http.CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Construct the REST path for the fhir-config document
            com.couchbase.client.java.http.HttpResponse httpResponse = httpClient.get(
                com.couchbase.client.java.http.HttpTarget.manager(),
                com.couchbase.client.java.http.HttpPath.of(
                    "/pools/default/buckets/{}/scopes/Admin/collections/config/docs/fhir-config",
                    bucketName
                )
            );
            
            int statusCode = httpResponse.statusCode();
            
            // If we get a 200, the document exists (FHIR-enabled)
            if (statusCode == 200) {
                return true;
            }
            
            // 404 means document doesn't exist (not FHIR-enabled)
            if (statusCode == 404) {
                return false;
            }
            
            // Other status codes are unexpected
            log.warn("Unexpected status code {} when checking FHIR config for bucket {}", statusCode, bucketName);
            return false;
            
        } catch (Exception e) {
            // Log the error but don't fail the check
            log.debug("REST check for FHIR config failed: {}", e.getMessage());
            return false;
        }
    }
}
