package com.couchbase.admin.buckets.service;

import com.couchbase.admin.buckets.model.BucketMetricsResponse;
import com.couchbase.admin.buckets.model.BucketMetricData;
import com.couchbase.admin.buckets.model.BucketMetricDataPoint;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.client.java.http.HttpPostOptions;
import com.couchbase.client.java.http.HttpBody;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BucketMetricsService {
    private static final Logger logger = LoggerFactory.getLogger(BucketMetricsService.class);

    @Autowired
    private ConnectionService connectionService;



    @Autowired
    private ObjectMapper objectMapper;

    // Bucket-level metric names based on user requirements
    private static final String[] BUCKET_METRIC_NAMES = {
        "kv_ops",                      // Total Operations
        "kv_vb_resident_items_ratio",  // Resident Ratio
        "kv_ep_cache_miss_ratio",      // Cache Miss Ratio
        "n1ql_request_time",           // Query Request Time in ns
        "n1ql_requests"                // N1QL Request Rate
    };

    public BucketMetricsResponse getBucketMetrics(String connectionName, String bucketName, String timeRange) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("Connection not found: " + connectionName);
            }

            // Calculate time parameters based on range
            Map<String, Object> timeParams = calculateTimeParameters(timeRange);
            
            // Build metrics requests
            List<Map<String, Object>> requests = buildMetricsRequests(bucketName, timeParams);
            
            // Fetch metrics from Couchbase using SDK's HTTP client
            List<Map<String, Object>> rawResponses = fetchMetricsFromCouchbase(cluster, requests);
            
            // Process and return structured response
            return processMetricsResponse(rawResponses);
            
        } catch (Exception e) {
            logger.error("Error fetching bucket metrics for connection: {} bucket: {}", connectionName, bucketName, e);
            throw new RuntimeException("Failed to fetch bucket metrics: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> buildMetricsRequests(String bucketName, Map<String, Object> timeParams) {
        List<Map<String, Object>> requests = new ArrayList<>();
        
        for (String metricName : BUCKET_METRIC_NAMES) {
            Map<String, Object> request = new HashMap<>();
            request.put("step", timeParams.get("step"));
            request.put("timeWindow", timeParams.get("timeWindow"));
            request.put("start", timeParams.get("start"));
            
            // Build metric labels
            List<Map<String, String>> metric = new ArrayList<>();
            metric.add(Map.of("label", "name", "value", metricName));
            
            // Add bucket label for specific metrics only (not for kv_ops or n1ql metrics based on working Postman payload)
            if (!"kv_ops".equals(metricName) && 
                !"n1ql_request_time".equals(metricName) && 
                !"n1ql_requests".equals(metricName)) {
                metric.add(Map.of("label", "bucket", "value", bucketName));
            }
            
            // Add state label for resident ratio
            if ("kv_vb_resident_items_ratio".equals(metricName)) {
                metric.add(Map.of("label", "state", "value", "active"));
            }
            
            request.put("metric", metric);
            
            // Set aggregation and functions based on metric type
            if ("kv_ops".equals(metricName) || "n1ql_requests".equals(metricName)) {
                request.put("nodesAggregation", "sum");
                request.put("applyFunctions", Arrays.asList("irate", "sum"));
            } else if ("n1ql_request_time".equals(metricName)) {
                // For n1ql time metrics, use irate like in working Postman payload
                request.put("applyFunctions", Arrays.asList("irate"));
                // No nodesAggregation for n1ql time metrics (matches Postman payload)
            } else {
                // request.put("nodesAggregation", "special");
                if ("kv_vb_resident_items_ratio".equals(metricName)) {
                    // No apply functions for resident ratio
                } else {
                    request.put("applyFunctions", Arrays.asList("irate"));
                }
            }
            
            request.put("alignTimestamps", true);
            requests.add(request);
        }
        
        return requests;
    }

    private Map<String, Object> calculateTimeParameters(String timeRange) {
        Map<String, Object> params = new HashMap<>();
        
        switch (timeRange.toUpperCase()) {
            case "MINUTE":
                // 1.5 minutes window, 9 data points, 10 second steps
                params.put("step", 3);           // 3 seconds
                params.put("timeWindow", 90);     // 1.5 minutes (not used but kept for consistency)
                params.put("start", -90);         // 1.5 minutes ago
                break;
            case "HOUR":
                // 1.5 hours window, 50 data points, 108 second steps
                params.put("step", 60);          // 60 seconds
                params.put("timeWindow", 5400);   // 1.5 hours (not used but kept for consistency)
                params.put("start", -5400);       // 1.5 hours ago
                break;
            case "DAY":
                // 1.5 days window, 50 data points, 2592 second steps
                params.put("step", 1440);         // 1440 seconds (24 minutes)
                params.put("timeWindow", 129600); // 1.5 days (not used but kept for consistency)
                params.put("start", -129600);     // 1.5 days ago
                break;
            case "WEEK":
                // 1.5 weeks window, 50 data points, 18144 second steps
                params.put("step", 10080);        // 10080 seconds (2.8 hours)
                params.put("timeWindow", 907200); // 1.5 weeks (not used but kept for consistency)
                params.put("start", -907200);     // 1.5 weeks ago
                break;
            case "MONTH":
                // 1.5 months window, 50 data points, 78624 second steps
                params.put("step", 43680);        // 43680 seconds (12.13 hours)
                params.put("timeWindow", 3931200); // 1.5 months (not used but kept for consistency)
                params.put("start", -3931200);    // 1.5 months ago
                break;
            default:
                // Default to hour
                params.put("step", 60);
                params.put("timeWindow", 5400);
                params.put("start", -5400);
        }
        
        return params;
    }

    private List<Map<String, Object>> fetchMetricsFromCouchbase(Cluster cluster, List<Map<String, Object>> requests) {
        try {
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Convert requests to JSON
            String requestBody = objectMapper.writeValueAsString(requests);
            
            // Make the request using SDK's HTTP client
            HttpResponse response = httpClient.post(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/stats/range/"),
                HttpPostOptions.httpPostOptions()
                    .body(HttpBody.json(requestBody))
                    .header("Content-Type", "application/json")
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Parse response as list of maps
                String responseBody = response.contentAsString();
                TypeReference<List<Map<String, Object>>> typeRef = new TypeReference<List<Map<String, Object>>>() {};
                return objectMapper.readValue(responseBody, typeRef);
            } else {
                throw new RuntimeException("Failed to fetch metrics: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            logger.error("Error fetching bucket metrics from Couchbase", e);
            throw new RuntimeException("Failed to fetch metrics from Couchbase: " + e.getMessage(), e);
        }
    }

    private BucketMetricsResponse processMetricsResponse(List<Map<String, Object>> rawResponses) {
        BucketMetricsResponse response = new BucketMetricsResponse();
        List<BucketMetricData> metricsList = new ArrayList<>();
        Set<Long> allTimestamps = new TreeSet<>(); // Use TreeSet to keep timestamps sorted
        
        for (int i = 0; i < rawResponses.size() && i < BUCKET_METRIC_NAMES.length; i++) {
            Map<String, Object> rawResponse = rawResponses.get(i);
            String metricName = BUCKET_METRIC_NAMES[i];
            
            BucketMetricData metricData = new BucketMetricData();
            metricData.setName(metricName);
            metricData.setLabel(getMetricLabel(metricName));
            metricData.setUnit(getMetricUnit(metricName));
            
            List<BucketMetricDataPoint> dataPoints = new ArrayList<>();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dataArray = (List<Map<String, Object>>) rawResponse.get("data");
            
            if (dataArray != null && !dataArray.isEmpty()) {
                Map<String, Object> firstData = dataArray.get(0);
                @SuppressWarnings("unchecked")
                List<List<Object>> values = (List<List<Object>>) firstData.get("values");
                
                if (values != null) {
                    for (List<Object> value : values) {
                        if (value.size() >= 2) {
                            long timestamp = ((Number) value.get(0)).longValue();
                            String valueStr = value.get(1).toString();
                            
                            Double numValue = null;
                            try {
                                numValue = Double.parseDouble(valueStr);
                                // Handle NaN values
                                if (numValue.isNaN() || numValue.isInfinite()) {
                                    numValue = null;
                                }
                                // Convert scientific notation for ratios (e.g., 1.00e+02 = 100)
                                // Ratios should be in percentage form already, but scientific notation needs conversion
                                if (metricName.contains("ratio") && numValue != null) {
                                    // If the value is in scientific notation like 1.00e+02, it's already 100
                                    // No conversion needed - the scientific notation parsing handles it
                                }
                            } catch (NumberFormatException e) {
                                // Keep as null for non-numeric values
                            }
                            
                            BucketMetricDataPoint dataPoint = new BucketMetricDataPoint();
                            dataPoint.setTimestamp(timestamp);
                            dataPoint.setValue(numValue);
                            dataPoints.add(dataPoint);
                            
                            allTimestamps.add(timestamp);
                        }
                    }
                }
            }
            
            metricData.setDataPoints(dataPoints);
            metricsList.add(metricData);
        }
        
        response.setMetrics(metricsList);
        response.setTimestamps(new ArrayList<>(allTimestamps));
        
        return response;
    }

    private String getMetricLabel(String metricName) {
        switch (metricName) {
            case "kv_ops":
                return "Total Ops";
            case "kv_vb_resident_items_ratio":
                return "Resident Ratio";
            case "kv_ep_cache_miss_ratio":
                return "Cache Miss Ratio";
            case "n1ql_request_time":
                return "Query Request Time";
            case "n1ql_requests":
                return "N1QL Request Rate";
            default:
                return metricName;
        }
    }

    private String getMetricUnit(String metricName) {
        switch (metricName) {
            case "kv_ops":
                return "ops/sec";
            case "kv_vb_resident_items_ratio":
            case "kv_ep_cache_miss_ratio":
                return "ratio";
            case "n1ql_request_time":
                return "ns";
            case "n1ql_requests":
                return "req/sec";
            default:
                return "";
        }
    }
}
