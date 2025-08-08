package com.couchbase.admin.fts.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.connections.service.ConnectionService.ConnectionDetails;
import com.couchbase.admin.fts.model.FtsMetricsRequest;
import com.couchbase.admin.fts.model.FtsMetricsResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FtsMetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsMetricsService.class);
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Autowired
    private ConnectionService connectionService;

    public FtsMetricsService() {
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }

    /**
     * Get FTS metrics for a specific index over a time range
     */
    public FtsMetricsResponse getFtsMetrics(String connectionName, String bucketName, 
                                          String indexName, FtsMetricsRequest.TimeRange timeRange) {
        try {
            ConnectionDetails connection = connectionService.getConnectionDetails(connectionName);
            if (connection == null) {
                logger.error("Connection not found: {}", connectionName);
                return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
            }

            // Build the metrics requests (array of requests)
            List<FtsMetricsRequest> requests = buildMetricsRequests(bucketName, indexName, timeRange);
            
            // Make the API call with array payload
            Map<String, Object> response = callMetricsApiWithArray(connection, requests);
            
            if (response == null) {
                return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
            }

            // Parse and return the response
            return parseMetricsResponse(response, bucketName, indexName, timeRange.getLabel());

        } catch (Exception e) {
            logger.error("Error retrieving FTS metrics for {}: {}", indexName, e.getMessage(), e);
            return getEmptyResponse(bucketName, indexName, timeRange.getLabel());
        }
    }

    /**
     * Build the metrics request payload - create separate requests for each metric
     */
    private List<FtsMetricsRequest> buildMetricsRequests(String bucketName, String indexName, 
                                                       FtsMetricsRequest.TimeRange timeRange) {
        List<FtsMetricsRequest> requests = new ArrayList<>();
        
        // Create separate request for each metric
        String[] metricNames = {
            "fts_total_queries",
            "fts_avg_queries_latency", 
            "fts_doc_count",
            "fts_num_bytes_used_disk",
            "fts_num_bytes_used_ram"
        };
        
        for (String metricName : metricNames) {
            List<FtsMetricsRequest.MetricFilter> metrics = new ArrayList<>();
            metrics.add(new FtsMetricsRequest.MetricFilter("name", metricName));
            metrics.add(new FtsMetricsRequest.MetricFilter("bucket", bucketName));
            // Format: {bucket}.Resources.{indexName}
            String fullIndexName = bucketName + ".Resources." + indexName;
            metrics.add(new FtsMetricsRequest.MetricFilter("index", fullIndexName));
            
            FtsMetricsRequest request = new FtsMetricsRequest(
                timeRange.getStep(),
                timeRange.getTimeWindow(),
                -timeRange.getTimeWindow(),
                metrics,
                "sum",
                true
            );
            
            requests.add(request);
        }
        
        // Built requests for 5 metrics
        return requests;
    }

    /**
     * Make the API call to get metrics with array payload
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callMetricsApiWithArray(ConnectionDetails connection, List<FtsMetricsRequest> requests) {
        try {
            // Determine port and protocol
            String connectionString = connection.getConnectionString();
            boolean isSSL = connection.isSslEnabled();
            int port = isSSL ? 18091 : 8091; // Management port
            String protocol = isSSL ? "https" : "http";
            
            // Extract hostname from connection string (format: hostname:port or hostname)
            String hostname = connectionString.split(":")[0];
            
            String url = String.format("%s://%s:%d/pools/default/stats/range/", 
                                     protocol, hostname, port);
            
            // Set up authentication
            String auth = connection.getUsername() + ":" + connection.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Basic " + encodedAuth);
            
            // Convert requests array to JSON string
            String requestJson = objectMapper.writeValueAsString(requests);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            
            // Making POST request to Couchbase metrics API
            
            ResponseEntity<?> response = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Response is an array, not a single object
                Object responseBody = response.getBody();
                // Successfully received metrics data
                
                // Wrap array response in a map for consistent handling
                Map<String, Object> wrappedResponse = new HashMap<>();
                wrappedResponse.put("responses", responseBody);
                return wrappedResponse;
            } else {
                logger.warn("Metrics API returned status: {}", response.getStatusCode());
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Error calling metrics API: {}", e.getMessage(), e);
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
            logger.error("Error parsing metrics response: {}", e.getMessage(), e);
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
            case "fts_total_queries": return "Total Queries";
            case "fts_avg_queries_latency": return "Avg Query Latency";
            case "fts_doc_count": return "Document Count";
            case "fts_num_bytes_used_disk": return "Disk Usage";
            case "fts_num_bytes_used_ram": return "RAM Usage";
            default: return metricName;
        }
    }
    
    /**
     * Get unit for metric name
     */
    private String getMetricUnit(String metricName) {
        switch (metricName) {
            case "fts_total_queries": return "queries";
            case "fts_avg_queries_latency": return "ms";
            case "fts_doc_count": return "docs";
            case "fts_num_bytes_used_disk":
            case "fts_num_bytes_used_ram": return "bytes";
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