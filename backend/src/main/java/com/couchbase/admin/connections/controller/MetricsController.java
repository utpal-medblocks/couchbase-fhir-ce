package com.couchbase.admin.connections.controller;

import com.couchbase.admin.connections.model.ClusterMetrics;
import com.couchbase.admin.connections.service.ClusterMetricsService;
import com.couchbase.admin.connections.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@CrossOrigin(origins = "http://localhost:5173")
public class MetricsController {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsController.class);
    
    @Autowired
    private ClusterMetricsService clusterMetricsService;
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Get cluster metrics for a specific connection
     * Now uses SDK HTTP client - no need to store connection details separately
     */
    @GetMapping("/cluster/{connectionName}")
    public ResponseEntity<ClusterMetrics> getClusterMetrics(@PathVariable String connectionName) {
        logger.info("Getting cluster metrics for connection: {}", connectionName);
        
        try {
            // Use the improved metrics service that leverages the SDK's HTTP client
            ClusterMetrics metrics = clusterMetricsService.getClusterMetrics(connectionName);
            return ResponseEntity.ok(metrics);
            
        } catch (Exception e) {
            logger.error("Error getting cluster metrics for {}: {}", connectionName, e.getMessage(), e);
            
            // Return default metrics on error
            ClusterMetrics defaultMetrics = clusterMetricsService.getDefaultMetrics();
            return ResponseEntity.ok(defaultMetrics);
        }
    }
    
    /**
     * Get metrics for all active connections
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, ClusterMetrics>> getAllMetrics() {
        logger.info("Getting metrics for all active connections");
        
        Map<String, ClusterMetrics> allMetrics = new HashMap<>();
        
        try {
            // Get all active connections
            var activeConnections = connectionService.getActiveConnections();
            
            for (String connectionName : activeConnections) {
                try {
                    ClusterMetrics metrics = clusterMetricsService.getClusterMetrics(connectionName);
                    allMetrics.put(connectionName, metrics);
                } catch (Exception e) {
                    logger.error("Error getting metrics for connection {}: {}", connectionName, e.getMessage());
                    // Continue with other connections
                }
            }
            
            return ResponseEntity.ok(allMetrics);
            
        } catch (Exception e) {
            logger.error("Error getting all metrics: {}", e.getMessage(), e);
            
            // Return empty map on error
            return ResponseEntity.ok(new HashMap<>());
        }
    }
    
    /**
     * Store connection details for metrics retrieval
     * DEPRECATED: No longer needed since we use SDK HTTP client
     * Keeping for backward compatibility during transition
     */
    @PostMapping("/connection-details")
    public ResponseEntity<Map<String, String>> storeConnectionDetails(@RequestBody ConnectionDetailsRequest request) {
        logger.info("Connection details storage request for: {} (deprecated - using SDK HTTP client)", request.name);
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Using SDK HTTP client - connection details storage not required");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Remove connection details when connection is deleted
     * DEPRECATED: No longer needed since we use SDK HTTP client
     * Keeping for backward compatibility during transition
     */
    @DeleteMapping("/connection-details/{connectionName}")
    public ResponseEntity<Map<String, String>> removeConnectionDetails(@PathVariable String connectionName) {
        logger.info("Connection details removal request for: {} (deprecated - using SDK HTTP client)", connectionName);
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Using SDK HTTP client - connection details removal not required");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get health status of metrics service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> getHealth() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "MetricsController");
        health.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        return ResponseEntity.ok(health);
    }
    
    // Inner classes for request/response handling (keeping for backward compatibility)
    public static class ConnectionDetailsRequest {
        public String name;
        public String connectionString;
        public String username;
        public String password;
        
        // Default constructor for JSON deserialization
        public ConnectionDetailsRequest() {}
        
        public ConnectionDetailsRequest(String name, String connectionString, String username, String password) {
            this.name = name;
            this.connectionString = connectionString;
            this.username = username;
            this.password = password;
        }
    }
} 