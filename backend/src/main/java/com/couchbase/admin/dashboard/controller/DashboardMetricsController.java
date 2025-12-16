package com.couchbase.admin.dashboard.controller;

import com.couchbase.admin.connections.service.ClusterMetricsService;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.connections.model.ClusterMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64;

// Add Docker and HAProxy client dependencies
// (You must add these to your pom.xml)
// Docker Java: com.github.docker-java:docker-java:3.2.13
// HAProxy Client: com.github.mjeanroy:haproxy-client:2.6.0

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardMetricsController {

    @Autowired
    private ClusterMetricsService clusterMetricsService;
    
    @Autowired
    private ConnectionService connectionService;

    @Value("${DEPLOYED_ENV:}")
    private String deployedEnv;

    @Autowired
    private Environment env;

    @GetMapping("/metrics")
    // @Cacheable(value = "dashboardMetrics", unless = "#result.body == null") // Temporarily disabled to test bucket discovery
    public ResponseEntity<ClusterMetrics> getDashboardMetrics(@RequestParam(required = false) String connectionName) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Get connection name - use provided or get the first active connection
            if (connectionName == null || connectionName.isEmpty() || "No Connection".equals(connectionName)) {
                List<String> activeConnections = connectionService.getActiveConnections();
                if (activeConnections.isEmpty()) {
                    return ResponseEntity.badRequest().build();
                }
                connectionName = activeConnections.get(0);
            }
            
            ClusterMetrics clusterMetrics = clusterMetricsService.getClusterMetrics(connectionName);
            
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(clusterMetrics);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthStatus() {
        Map<String, Object> healthResponse = new HashMap<>();
        healthResponse.put("status", "UP");
        healthResponse.put("details", Map.of("service", "FHIR Server", "status", "Running"));
        healthResponse.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(healthResponse);
    }

    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemMetrics() {
        Map<String, Object> systemResponse = new HashMap<>();
        systemResponse.put("message", "System metrics moved to HAProxy time-series endpoint");
        systemResponse.put("endpoint", "/api/metrics/haproxy-timeseries");
        systemResponse.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(systemResponse);
    }

    @GetMapping("/application")
    public ResponseEntity<Map<String, Object>> getApplicationMetrics() {
        Map<String, Object> appResponse = new HashMap<>();
        appResponse.put("applicationInfo", Map.of("name", "FHIR Server", "version", "1.0.0"));
        appResponse.put("message", "Application metrics moved to HAProxy time-series endpoint");
        appResponse.put("endpoint", "/api/metrics/haproxy-timeseries");
        appResponse.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(appResponse);
    }

    @GetMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshMetrics() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Metrics auto-refresh every 6 seconds via HAProxy service");
        response.put("endpoint", "/api/metrics/haproxy-timeseries");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("health", "UP");
        summary.put("service", "FHIR Server");
        summary.put("message", "Full metrics available at HAProxy time-series endpoint");
        summary.put("endpoint", "/api/metrics/haproxy-timeseries");
        summary.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(summary);
    }

    private boolean isRunningInContainer() {
        // Check both env and file system for container clues
        String envValue = Optional.ofNullable(System.getenv("DEPLOYED_ENV")).orElse("");
        if ("container".equalsIgnoreCase(envValue)) return true;
        if (Files.exists(Paths.get("/.dockerenv"))) return true;
        return false;
    }

    @GetMapping("/fhir-metrics")
    public ResponseEntity<Map<String, Object>> getFhirMetrics() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "FHIR metrics moved to HAProxy time-series endpoint");
        response.put("endpoint", "/api/metrics/haproxy-timeseries");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/haproxy-metrics")
    public ResponseEntity<Map<String, Object>> getHaproxyMetrics() {
        try {
            // Check if running in containerized environment
            // Use internal stats port (8404) to avoid HTTPS redirects
            String haproxyUrl = isRunningInContainer() 
                ? "http://haproxy:8404/haproxy?stats;csv"  // Internal container network on stats port
                : "http://localhost/haproxy?stats;csv"; // Development mode
            
            Map<String, Object> haproxyStats = fetchHaproxyStats(haproxyUrl);
            
            Map<String, Object> response = new HashMap<>();
            response.put("haproxy", haproxyStats);
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Only log the error message, not the full stack trace to reduce noise
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "HAProxy metrics unavailable");
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
        }
    }

    private Map<String, Object> fetchHaproxyStats(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        // Add basic auth for HAProxy stats
        String auth = "admin:admin";
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
        
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HAProxy stats returned HTTP " + responseCode);
        }
        
        // Read CSV response with proper resource management
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            lines = reader.lines().collect(Collectors.toList());
        } finally {
            connection.disconnect();
        }
        
        return parseHaproxyCSV(lines);
    }

    private Map<String, Object> parseHaproxyCSV(List<String> lines) {
        Map<String, Object> result = new HashMap<>();
        
        if (lines.isEmpty()) {
            return result;
        }
        
        // Skip comment line and get headers
        String[] headers = null;
        List<String[]> dataRows = new ArrayList<>();
        
        for (String line : lines) {
            if (line.startsWith("#")) {
                // Extract headers from comment line (remove # and split)
                headers = line.substring(2).split(",");
                continue;
            }
            if (!line.trim().isEmpty()) {
                dataRows.add(line.split(","));
            }
        }
        
        if (headers == null || dataRows.isEmpty()) {
            return result;
        }
        
        // Parse stats by service
        Map<String, Map<String, Object>> services = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();
        
        long totalRequests = 0;
        long totalSessions = 0;
        double totalResponseTime = 0;
        int serviceCount = 0;
        
        for (String[] row : dataRows) {
            if (row.length < headers.length) continue;
            
            String serviceName = row[0]; // pxname
            String serverName = row[1];  // svname
            
            if ("FRONTEND".equals(serverName) || "BACKEND".equals(serverName)) {
                Map<String, Object> serviceStats = new HashMap<>();
                
                for (int i = 0; i < Math.min(headers.length, row.length); i++) {
                    String value = row[i];
                    if (!value.isEmpty()) {
                        // Convert numeric values
                        try {
                            if (headers[i].equals("stot") || headers[i].equals("req_tot") || 
                                headers[i].equals("hrsp_2xx") || headers[i].equals("hrsp_3xx") ||
                                headers[i].equals("hrsp_4xx") || headers[i].equals("hrsp_5xx")) {
                                long numValue = Long.parseLong(value);
                                serviceStats.put(headers[i], numValue);
                                if (headers[i].equals("stot")) {
                                    totalRequests += numValue;
                                }
                            } else if (headers[i].equals("scur") || headers[i].equals("smax")) {
                                long numValue = Long.parseLong(value);
                                serviceStats.put(headers[i], numValue);
                                if (headers[i].equals("scur")) {
                                    totalSessions += numValue;
                                }
                            } else {
                                serviceStats.put(headers[i], value);
                            }
                        } catch (NumberFormatException e) {
                            serviceStats.put(headers[i], value);
                        }
                    }
                }
                
                services.put(serviceName + "_" + serverName.toLowerCase(), serviceStats);
                if ("BACKEND".equals(serverName)) {
                    serviceCount++;
                }
            }
        }
        
        // Create summary stats
        summary.put("totalRequests", totalRequests);
        summary.put("totalSessions", totalSessions);
        summary.put("activeServices", serviceCount);
        summary.put("timestamp", System.currentTimeMillis());
        
        result.put("services", services);
        result.put("summary", summary);
        
        return result;
    }
}
