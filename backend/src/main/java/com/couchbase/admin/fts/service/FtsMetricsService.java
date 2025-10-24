package com.couchbase.admin.fts.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.fts.model.FtsMetricsRequest;
import com.couchbase.admin.fts.model.FtsMetricsResponse;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.client.java.http.HttpPostOptions;
import com.couchbase.client.java.http.HttpBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FtsMetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsMetricsService.class);
    private final ObjectMapper objectMapper;

    @Autowired
    private ConnectionService connectionService;

    public FtsMetricsService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get FTS metrics for a specific index over a time range
     */
    public FtsMetricsResponse getFtsMetrics(String connectionName, String bucketName, 
                                          String indexName, FtsMetricsRequest.TimeRange timeRange) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                logger.error("Connection not found: {}", connectionName);
                return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
            }

            // Build the metrics requests (array of requests)
            List<FtsMetricsRequest> requests = buildMetricsRequests(bucketName, indexName, timeRange);
            
            // Make the API call with array payload using SDK's HTTP client
            Map<String, Object> response = callMetricsApiWithArray(cluster, requests);
            
            if (response == null) {
                return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
            }

            // Parse and return the response
            return parseMetricsResponse(response, bucketName, indexName, timeRange.getLabel());

        } catch (Exception e) {
            logger.error("Error retrieving FTS metrics for {}: {}", indexName, e.getMessage());
            return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
        }
    }

    /**
     * Build the metrics request payload - create separate requests for each metric
     */
    private List<FtsMetricsRequest> buildMetricsRequests(String bucketName, String indexName, 
                                                       FtsMetricsRequest.TimeRange timeRange) {
        List<FtsMetricsRequest> requests = new ArrayList<>();
        
        // Updated metric names according to new requirements
        String[] metricNames = {
            "fts_total_queries",                // Chart 1: Search Query Rate (with irate)
            "fts_total_queries_error",         // Chart 1: Search Query Error Rate (with irate) 
            "fts_avg_queries_latency",         // Chart 2: Average Latency (with irate)
            "fts_doc_count",                   // Chart 3: Document Count (NO irate)
            "fts_num_mutations_to_index",      // Chart 4: Mutation Remaining (NO irate)
            "fts_num_recs_to_persist"          // Chart 4: Docs Remaining (NO irate)
        };
        
        for (String metricName : metricNames) {
            List<FtsMetricsRequest.MetricFilter> metrics = new ArrayList<>();
            metrics.add(new FtsMetricsRequest.MetricFilter("name", metricName));
            metrics.add(new FtsMetricsRequest.MetricFilter("bucket", bucketName));
            // Format: {bucket}.Resources.{indexName}
            String fullIndexName = bucketName + ".Resources." + indexName;
            metrics.add(new FtsMetricsRequest.MetricFilter("index", fullIndexName));
            
            FtsMetricsRequest request = new FtsMetricsRequest();
            request.setStep(timeRange.getStep());
            request.setTimeWindow(timeRange.getTimeWindow());
            request.setStart(-timeRange.getTimeWindow());
            request.setMetric(metrics);
            request.setNodesAggregation("sum");
            request.setAlignTimestamps(true);
            
            // Apply irate function to specific metrics as specified:
            // Chart 1 & 2: with irate, Chart 3 & 4: without irate
            if ("fts_total_queries".equals(metricName) || 
                "fts_total_queries_error".equals(metricName) ||
                "fts_avg_queries_latency".equals(metricName)) {
                request.setApplyFunctions(Arrays.asList("irate"));
            }
            // fts_doc_count, fts_num_mutations_to_index, fts_num_recs_to_persist do NOT get irate
            
            requests.add(request);
        }
        
        // Built requests for 6 metrics (updated from 4)
        return requests;
    }

    /**
     * Make the API call to get metrics with array payload using SDK's HTTP client
     */
    private Map<String, Object> callMetricsApiWithArray(Cluster cluster, List<FtsMetricsRequest> requests) {
        try {
            // Use the cluster's HTTP client - this handles SSL certificates automatically
            CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Convert requests array to JSON string
            String requestJson = objectMapper.writeValueAsString(requests);
            
            // Make the API call using SDK's HTTP client
            HttpResponse response = httpClient.post(
                HttpTarget.manager(),
                HttpPath.of("/pools/default/stats/range/"),
                HttpPostOptions.httpPostOptions()
                    .body(HttpBody.json(requestJson))
                    .header("Content-Type", "application/json")
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Response is an array, not a single object
                String responseBody = response.contentAsString();
                Object responseData = objectMapper.readValue(responseBody, Object.class);
                
                // Wrap array response in a map for consistent handling
                Map<String, Object> wrappedResponse = new HashMap<>();
                wrappedResponse.put("responses", responseData);
                return wrappedResponse;
            } else {
                logger.warn("Metrics API returned status: {}", response.statusCode());
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Error calling metrics API: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the metrics API response - now expects array of responses
     */
    @SuppressWarnings("unchecked")
    private FtsMetricsResponse parseMetricsResponse(Map<String, Object> response, 
                                                   String bucketName, String indexName, String timeRange) {
        try {
            List<FtsMetricsResponse.MetricData> metricDataList = new ArrayList<>();
            
            // The response is wrapped - extract the array
            if (response.containsKey("responses")) {
                List<Map<String, Object>> responseArray = (List<Map<String, Object>>) response.get("responses");
                // Processing metric responses
                
                // Parse each metric response
                for (Map<String, Object> metricResponse : responseArray) {
                    if (metricResponse.containsKey("data")) {
                        List<Map<String, Object>> dataArray = (List<Map<String, Object>>) metricResponse.get("data");
                        
                        // Each data array should contain one metric
                        for (Map<String, Object> metricItem : dataArray) {
                            if (metricItem.containsKey("metric") && metricItem.containsKey("values")) {
                                Map<String, Object> metric = (Map<String, Object>) metricItem.get("metric");
                                List<List<Object>> values = (List<List<Object>>) metricItem.get("values");
                                
                                // Extract metric name
                                String metricName = (String) metric.get("name");
                                if (metricName != null) {
                                    FtsMetricsResponse.MetricData parsedMetric = parseMetricItem(metricName, values);
                                    if (parsedMetric != null) {
                                        metricDataList.add(parsedMetric);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Parsed all metrics successfully
            return new FtsMetricsResponse(metricDataList, timeRange, bucketName, indexName);
            
        } catch (Exception e) {
            logger.error("Error parsing metrics response: {}", e.getMessage());
            return getEmptyResponse(bucketName, indexName, timeRange);
        }
    }

    /**
     * Parse a specific metric item from the Couchbase response
     */
    private FtsMetricsResponse.MetricData parseMetricItem(String metricName, List<List<Object>> values) {
        try {
            List<FtsMetricsResponse.DataPoint> dataPoints = new ArrayList<>();
            
            // Convert values array to data points
            for (List<Object> valueEntry : values) {
                if (valueEntry.size() >= 2) {
                    // First element is timestamp (seconds), second is value (as string)
                    long timestamp = ((Number) valueEntry.get(0)).longValue() * 1000; // Convert to milliseconds
                    String valueStr = (String) valueEntry.get(1);
                    
                    try {
                        double value = Double.parseDouble(valueStr);
                        dataPoints.add(new FtsMetricsResponse.DataPoint(timestamp, value));
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse value '{}' for metric {}", valueStr, metricName);
                    }
                }
            }
            
            // Determine label and unit based on metric name
            String label = getMetricLabel(metricName);
            String unit = getMetricUnit(metricName);
            
            return new FtsMetricsResponse.MetricData(metricName, label, dataPoints, unit);
            
        } catch (Exception e) {
            logger.error("Error parsing metric item {}: {}", metricName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get human-readable label for metric name
     */
    private String getMetricLabel(String metricName) {
        switch (metricName) {
            case "fts_total_queries": return "Search Query Rate";
            case "fts_total_queries_error": return "Search Query Error Rate";
            case "fts_avg_queries_latency": return "Average Latency";
            case "fts_doc_count": return "Document Count";
            case "fts_num_mutations_to_index": return "Mutation Remaining";
            case "fts_num_recs_to_persist": return "Docs Remaining";
            default: return metricName;
        }
    }
    
    /**
     * Get unit for metric name
     */
    private String getMetricUnit(String metricName) {
        switch (metricName) {
            case "fts_total_queries": return "queries";
            case "fts_total_queries_error": return "queries";
            case "fts_avg_queries_latency": return "ms";
            case "fts_doc_count": return "docs";
            case "fts_num_mutations_to_index": return "count";
            case "fts_num_recs_to_persist": return "count";
            default: return "";
        }
    }

    /**
     * Get empty response for error cases
     */
    private FtsMetricsResponse getEmptyResponse(String bucketName, String indexName, String timeRange) {
        return new FtsMetricsResponse(new ArrayList<>(), timeRange, bucketName, indexName);
    }
}