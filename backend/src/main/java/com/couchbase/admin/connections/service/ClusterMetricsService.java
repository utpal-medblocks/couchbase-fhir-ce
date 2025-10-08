package com.couchbase.admin.connections.service;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.admin.connections.model.ClusterMetrics;
import com.couchbase.admin.fhirBucket.service.FhirBucketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClusterMetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(ClusterMetricsService.class);
    private final ObjectMapper objectMapper;
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirBucketService fhirBucketService;
    
    public ClusterMetricsService() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Get cluster metrics using the cluster's built-in HTTP client
     * This approach works with DNS SRV records, Capella, and self-managed clusters
     * OPTIMIZED: Single API call to /pools/default gets all data
     */
    public ClusterMetrics getClusterMetrics(String connectionName) {
        try {
            // Get the active cluster connection
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                logger.warn("No active connection found for: {}", connectionName);
                return getDefaultMetrics();
            }
            
            // Use the cluster's HTTP client - this handles DNS SRV resolution automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // SINGLE API CALL: Get everything from /pools/default
            HttpResponse response = httpClient.get(
                HttpTarget.manager(),
                HttpPath.of("/pools/default")
            );
            
            if (response.statusCode() == 200) {
                String responseBody = response.contentAsString();
                JsonNode poolsData = objectMapper.readTree(responseBody);
                
                // Extract all data from single response
                String clusterName = extractClusterName(poolsData);
                List<ClusterMetrics.NodeMetrics> nodes = extractNodeMetrics(poolsData);
                List<ClusterMetrics.BucketMetrics> buckets = extractBucketMetrics(poolsData, httpClient, connectionName);
                ClusterMetrics.StorageTotals storageTotals = extractStorageTotals(poolsData);
                List<ClusterMetrics.ClusterAlert> alerts = extractAlerts(poolsData);
                ClusterMetrics.ServiceQuotas serviceQuotas = extractServiceQuotas(poolsData);
                
                ClusterMetrics result = new ClusterMetrics(nodes, buckets, clusterName, storageTotals, alerts, serviceQuotas);
                
                return result;
            } else {
                logger.warn("Failed to get cluster data, status code: {}", response.statusCode());
                return getDefaultMetrics();
            }
            
        } catch (Exception e) {
            logger.error("Error retrieving cluster metrics for {}: {}", connectionName, e.getMessage(), e);
            return getDefaultMetrics();
        }
    }
    
    /**
     * Extract cluster name from pools/default response
     */
    private String extractClusterName(JsonNode poolsData) {
        try {
            if (poolsData.has("clusterName")) {
                String clusterName = poolsData.get("clusterName").asText();
                if (clusterName != null && !clusterName.isEmpty()) {
                    return clusterName;
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting cluster name: {}", e.getMessage());
        }
        return "Couchbase Cluster";
    }

    /**
     * Extract node metrics from pools/default response
     */
    private List<ClusterMetrics.NodeMetrics> extractNodeMetrics(JsonNode poolsData) {
        List<ClusterMetrics.NodeMetrics> nodes = new ArrayList<>();
        
        try {
            JsonNode nodesArray = poolsData.get("nodes");
            if (nodesArray != null && nodesArray.isArray()) {
                for (JsonNode nodeData : nodesArray) {
                    ClusterMetrics.NodeMetrics nodeMetrics = parseNodeMetrics(nodeData);
                    if (nodeMetrics != null) {
                        nodes.add(nodeMetrics);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting node metrics: {}", e.getMessage());
        }
        
        return nodes;
    }

    /**
     * Extract bucket metrics - try detailed buckets first, fallback to bucket names
     */
    private List<ClusterMetrics.BucketMetrics> extractBucketMetrics(JsonNode poolsData, CouchbaseHttpClient httpClient, String connectionName) {
        List<ClusterMetrics.BucketMetrics> buckets = new ArrayList<>();
        
        try {
            // Use enhanced buckets API with essential stats
            // logger.info("Attempting to fetch enhanced bucket metrics from /pools/default/buckets?skipMap=true&basic_stats=true");
            HttpResponse bucketsResponse = httpClient.get(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/buckets?skipMap=true&basic_stats=true")
            );
            
            // logger.info("Enhanced buckets API response status: {}", bucketsResponse.statusCode());
            
            if (bucketsResponse.statusCode() == 200) {
                String bucketsResponseBody = bucketsResponse.contentAsString();
                // logger.info("Enhanced buckets API response body length: {}", bucketsResponseBody.length());
                // logger.debug("Enhanced buckets API response: {}", bucketsResponseBody);
                
                JsonNode bucketsArray = objectMapper.readTree(bucketsResponseBody);
                
                if (bucketsArray.isArray()) {
                    // logger.info("Found {} buckets in enhanced response", bucketsArray.size());
                    for (JsonNode bucketData : bucketsArray) {
                        ClusterMetrics.BucketMetrics bucketMetrics = parseEnhancedBucketMetrics(bucketData, connectionName);
                        if (bucketMetrics != null) {
                            // logger.info("Parsed enhanced bucket: {} with RAM {}/{} MB, Ops/sec: {}, Resident Ratio: {}%", 
                            //     bucketMetrics.getName(), bucketMetrics.getRamUsed(), bucketMetrics.getRamQuota(),
                            //     bucketMetrics.getOpsPerSec(), String.format("%.1f", bucketMetrics.getResidentRatio()));
                            buckets.add(bucketMetrics);
                        }
                    }
                    // logger.info("Successfully extracted {} enhanced buckets", buckets.size());
                    return buckets; // Return enhanced bucket data
                } else {
                    logger.warn("Enhanced buckets response is not an array: {}", bucketsArray.getNodeType());
                }
            } else {
                logger.warn("Failed to get enhanced buckets, status: {}, response: {}", 
                    bucketsResponse.statusCode(), bucketsResponse.contentAsString());
            }
            
            // Fallback: extract basic bucket names from pools/default
            logger.warn("Using fallback bucket extraction from pools/default - THIS CREATES LIMITED DATA!");
            JsonNode bucketNames = poolsData.get("bucketNames");
            if (bucketNames != null && bucketNames.isArray()) {
                logger.info("Found {} bucket names in fallback", bucketNames.size());
                for (JsonNode bucketInfo : bucketNames) {
                    String bucketName = bucketInfo.get("bucketName").asText();
                    
                    // Check FHIR status for this bucket
                    Boolean isFhirBucket = null;
                    try {
                        isFhirBucket = fhirBucketService.isFhirBucket(bucketName, connectionName);
                    } catch (Exception e) {
                        logger.warn("Failed to check FHIR status for bucket {}: {}", bucketName, e.getMessage());
                    }
                    
                    // No GSI indexes in FTS-only architecture - always ready
                    String bucketStatus = "Ready";
                    
                    // Create bucket metrics with placeholder values
                    ClusterMetrics.BucketMetrics bucketMetrics = new ClusterMetrics.BucketMetrics(
                        bucketName, 0, 0, 0, 0, 0.0, 0, 100.0, 0.0, 0, 0, null, isFhirBucket, bucketStatus
                    );
                    logger.info("Created fallback bucket: {} with limited data", bucketName);
                    buckets.add(bucketMetrics);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error extracting enhanced bucket metrics: {}", e.getMessage(), e);
        }
        
        return buckets;
    }

    /**
     * Extract storage totals from pools/default response
     */
    private ClusterMetrics.StorageTotals extractStorageTotals(JsonNode poolsData) {
        try {
            JsonNode storageTotalsNode = poolsData.get("storageTotals");
            if (storageTotalsNode != null) {
                return objectMapper.treeToValue(storageTotalsNode, ClusterMetrics.StorageTotals.class);
            }
        } catch (Exception e) {
            logger.error("Error extracting storage totals: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract alerts from pools/default response
     */
    private List<ClusterMetrics.ClusterAlert> extractAlerts(JsonNode poolsData) {
        List<ClusterMetrics.ClusterAlert> alerts = new ArrayList<>();
        
        try {
            JsonNode alertsNode = poolsData.get("alerts");
            if (alertsNode != null && alertsNode.isArray()) {
                for (JsonNode alertData : alertsNode) {
                    ClusterMetrics.ClusterAlert alert = objectMapper.treeToValue(alertData, ClusterMetrics.ClusterAlert.class);
                    if (alert != null) {
                        alerts.add(alert);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting alerts: {}", e.getMessage());
        }
        
        return alerts;
    }
    
    /**
     * Parse individual node metrics from JSON
     */
    private ClusterMetrics.NodeMetrics parseNodeMetrics(JsonNode nodeData) {
        try {
            // logger.info("Raw node data: {}", nodeData.toString());
            // Use configuredHostname if available, fallback to hostname
            String rawHostname = nodeData.has("configuredHostname") ? 
                nodeData.get("configuredHostname").asText() : 
                nodeData.get("hostname").asText();
            
            // Clean up the hostname for display
            String hostname = cleanHostnameForDisplay(rawHostname);
                
            String status = nodeData.get("status").asText("healthy");
            String version = nodeData.has("version") ? nodeData.get("version").asText() : null;
            
            // Get system stats
            JsonNode systemStats = nodeData.get("systemStats");
            int cpu = systemStats != null ? systemStats.get("cpu_cores_available").asInt(4) : 4;
            
            // Convert bytes to GB for memory
            double memTotalBytes = systemStats != null ? systemStats.get("mem_total").asDouble(16000000000.0) : 16000000000.0;
            double ram = memTotalBytes / (1024.0 * 1024.0 * 1024.0);
            
            // Get utilization rates
            double cpuUtilization = systemStats != null ? systemStats.get("cpu_utilization_rate").asDouble(25.0) : 25.0;
            
            // Calculate RAM utilization from mem_total and mem_free
            double ramUtilization = 0.0; // Default
            if (systemStats != null && systemStats.has("mem_total") && systemStats.has("mem_free")) {
                double memTotal = systemStats.get("mem_total").asDouble();
                double memFree = systemStats.get("mem_free").asDouble();
                if (memTotal > 0) {
                    ramUtilization = ((memTotal - memFree) / memTotal) * 100.0;
                }
            }
            
            // Get services - the API returns them as an array
            JsonNode servicesNode = nodeData.get("services");
            List<String> services = new ArrayList<>();
            if (servicesNode != null && servicesNode.isArray()) {
                for (JsonNode service : servicesNode) {
                    services.add(service.asText());
                }
            }
            
            // For single-node clusters, disk utilization is 0 by default
            // This will be updated when we have storage totals per node
            double diskUtilizationRate = 0.0;
            
            return new ClusterMetrics.NodeMetrics(hostname, status, cpu, ram, cpuUtilization, ramUtilization, services, version, diskUtilizationRate);
            
        } catch (Exception e) {
            logger.error("Error parsing node metrics: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Clean up hostname for display purposes
     * Examples:
     * - svc-dqis-node-004.ubypte9g5yzpufiz.cloud.couchbase.com:8091 → svc-dqis-node-004
     * - ec2-174-129-64-176.compute-1.amazonaws.com:8091 → ec2-174-129-64-176
     * - 127.0.0.1:8091 → 127.0.0.1
     * - localhost:8091 → localhost
     */
    private String cleanHostnameForDisplay(String rawHostname) {
        if (rawHostname == null || rawHostname.trim().isEmpty()) {
            return rawHostname;
        }
        
        try {
            // Remove port number if present
            String hostname = rawHostname.split(":")[0];
            
            // For Capella hostnames (*.cloud.couchbase.com), extract just the node identifier
            if (hostname.contains(".cloud.couchbase.com")) {
                String[] parts = hostname.split("\\.");
                if (parts.length > 0) {
                    return parts[0]; // Return just the node identifier (e.g., svc-dqis-node-004)
                }
            }
            
            // For AWS hostnames (*.amazonaws.com), extract just the EC2 instance identifier
            if (hostname.contains(".amazonaws.com")) {
                String[] parts = hostname.split("\\.");
                if (parts.length > 0) {
                    return parts[0]; // Return just the EC2 instance identifier (e.g., ec2-174-129-64-176)
                }
            }
            
            // For other hostnames (local, self-managed), return as-is after port removal
            return hostname;
            
        } catch (Exception e) {
            logger.warn("Error cleaning hostname '{}': {}", rawHostname, e.getMessage());
            return rawHostname; // Return original if cleaning fails
        }
    }
    
    /**
     * Parse enhanced bucket metrics from JSON with all statistics
     */
    private ClusterMetrics.BucketMetrics parseEnhancedBucketMetrics(JsonNode bucketData, String connectionName) {
        try {
            String name = bucketData.get("name").asText();
            // logger.debug("Parsing enhanced bucket: {}", name);
            
            // Basic bucket info
            if (!bucketData.has("quota") || !bucketData.get("quota").has("ram")) {
                logger.error("Enhanced bucket {} missing quota.ram field", name);
                return null;
            }
            
            // Log raw values for debugging
            long rawRamQuota = bucketData.get("quota").get("ram").asLong();
            int ramQuota = (int)(rawRamQuota / (1024 * 1024)); // Convert to MB
            
            // Get basic stats
            JsonNode basicStats = bucketData.get("basicStats");
            if (basicStats == null) {
                logger.error("Enhanced bucket {} missing basicStats field", name);
                return null;
            }
            
            // Essential metrics
            long rawMemUsed = basicStats.has("memUsed") ? basicStats.get("memUsed").asLong() : 0;
            int ramUsed = (int)(rawMemUsed / (1024 * 1024)); // Convert to MB
            long itemCount = basicStats.has("itemCount") ? basicStats.get("itemCount").asLong() : 0;
            long diskUsed = basicStats.has("diskUsed") ? basicStats.get("diskUsed").asLong() : 0;
            
            // Enhanced metrics from your API
            double opsPerSec = basicStats.has("opsPerSec") ? basicStats.get("opsPerSec").asDouble() : 0.0;
            long diskFetches = basicStats.has("diskFetches") ? basicStats.get("diskFetches").asLong() : 0;
            double quotaPercentUsed = basicStats.has("quotaPercentUsed") ? basicStats.get("quotaPercentUsed").asDouble() : 0.0;
            long dataUsed = basicStats.has("dataUsed") ? basicStats.get("dataUsed").asLong() : 0;
            long vbActiveNumNonResident = basicStats.has("vbActiveNumNonResident") ? basicStats.get("vbActiveNumNonResident").asLong() : 0;
            
            // Calculate resident ratio: (itemCount - vbActiveNumNonResident)/itemCount * 100
            // If itemCount = 0, then residentRatio = 100
            double residentRatio = 100.0; // Default to 100% for empty buckets
            if (itemCount > 0) {
                residentRatio = ((double)(itemCount - vbActiveNumNonResident) / itemCount) * 100.0;
            }
            
            // Extract bucket-level storage totals if available
            ClusterMetrics.StorageTotals bucketStorageTotals = null;
            if (basicStats.has("storageTotals")) {
                try {
                    bucketStorageTotals = objectMapper.treeToValue(basicStats.get("storageTotals"), ClusterMetrics.StorageTotals.class);
                } catch (Exception e) {
                    logger.warn("Could not parse bucket storage totals for {}: {}", name, e.getMessage());
                }
            }
            
            // Check FHIR status for this bucket
            Boolean isFhirBucket = null;
            try {
                isFhirBucket = fhirBucketService.isFhirBucket(name, connectionName);
            } catch (Exception e) {
                logger.warn("Failed to check FHIR status for bucket {}: {}", name, e.getMessage());
            }
            
            // No GSI indexes in FTS-only architecture - always ready
            String bucketStatus = "Ready";
            
            // logger.info("Enhanced bucket {} stats - RAM: {}/{} MB, Items: {}, Ops/sec: {}, Disk Fetches: {}, Resident Ratio: {}%, Quota Used: {}%", 
            //     name, ramUsed, ramQuota, itemCount, opsPerSec, diskFetches, String.format("%.1f", residentRatio), String.format("%.1f", quotaPercentUsed));
            
            ClusterMetrics.BucketMetrics result = new ClusterMetrics.BucketMetrics(name, ramQuota, ramUsed, itemCount, diskUsed,
                                                                                   opsPerSec, diskFetches, residentRatio, quotaPercentUsed,
                                                                                   dataUsed, vbActiveNumNonResident, bucketStorageTotals, isFhirBucket, bucketStatus);
            
            // logger.info("Successfully parsed enhanced bucket: {} with full statistics", name);
            return result;
            
        } catch (Exception e) {
            logger.error("Error parsing enhanced bucket metrics: {}", e.getMessage(), e);
            logger.error("Enhanced bucket data that failed to parse: {}", bucketData.toString());
            return null;
        }
    }
    
    /**
     * Get default metrics when connection fails
     */
    public ClusterMetrics getDefaultMetrics() {
        return new ClusterMetrics(new ArrayList<>(), new ArrayList<>(), "Couchbase Cluster (Unavailable)");
    }

    /**
     * Extract service quotas from pools/default response
     */
    private ClusterMetrics.ServiceQuotas extractServiceQuotas(JsonNode poolsData) {
        try {
            int memoryQuota = poolsData.has("memoryQuota") ? poolsData.get("memoryQuota").asInt() : 0;
            int queryMemoryQuota = poolsData.has("queryMemoryQuota") ? poolsData.get("queryMemoryQuota").asInt() : 0;
            int indexMemoryQuota = poolsData.has("indexMemoryQuota") ? poolsData.get("indexMemoryQuota").asInt() : 0;
            int ftsMemoryQuota = poolsData.has("ftsMemoryQuota") ? poolsData.get("ftsMemoryQuota").asInt() : 0;
            int cbasMemoryQuota = poolsData.has("cbasMemoryQuota") ? poolsData.get("cbasMemoryQuota").asInt() : 0;
            int eventingMemoryQuota = poolsData.has("eventingMemoryQuota") ? poolsData.get("eventingMemoryQuota").asInt() : 0;
            
            // logger.info("Extracted service quotas - Memory: {}MB, Query: {}MB, Index: {}MB, FTS: {}MB, CBAS: {}MB, Eventing: {}MB",
            //     memoryQuota, queryMemoryQuota, indexMemoryQuota, ftsMemoryQuota, cbasMemoryQuota, eventingMemoryQuota);
                
            return new ClusterMetrics.ServiceQuotas(memoryQuota, queryMemoryQuota, indexMemoryQuota, 
                                                   ftsMemoryQuota, cbasMemoryQuota, eventingMemoryQuota);
        } catch (Exception e) {
            logger.error("Error extracting service quotas: {}", e.getMessage());
        }
        return null;
    }

    // GSI index status checking removed - FTS-only architecture
}