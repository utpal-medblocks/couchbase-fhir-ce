package com.couchbase.admin.dashboard.controller;

import com.couchbase.admin.dashboard.model.DashboardMetrics;
import com.couchbase.admin.dashboard.service.ActuatorAggregatorService;
import com.couchbase.admin.connections.service.ClusterMetricsService;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.connections.model.ClusterMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardMetricsController {

    @Autowired
    private ActuatorAggregatorService actuatorAggregatorService;
    
    @Autowired
    private ClusterMetricsService clusterMetricsService;
    
    @Autowired
    private ConnectionService connectionService;

    @GetMapping("/metrics")
    // @Cacheable(value = "dashboardMetrics", unless = "#result.body == null") // Temporarily disabled to test bucket discovery
    public ResponseEntity<ClusterMetrics> getDashboardMetrics(@RequestParam(required = false) String connectionName) {
        long startTime = System.currentTimeMillis();
        // System.out.println("🚀 DashboardMetricsController: Getting Couchbase cluster metrics");
        
        try {
            // Get connection name - use provided or get the first active connection
            if (connectionName == null || connectionName.isEmpty()) {
                List<String> activeConnections = connectionService.getActiveConnections();
                if (activeConnections.isEmpty()) {
                    System.out.println("❌ No active Couchbase connections found");
                    return ResponseEntity.badRequest().build();
                }
                connectionName = activeConnections.get(0);
            }
            
            // System.out.println("🔍 Getting cluster metrics for connection: " + connectionName);
            ClusterMetrics clusterMetrics = clusterMetricsService.getClusterMetrics(connectionName);
            
            long endTime = System.currentTimeMillis();
            // System.out.println("✅ DashboardMetricsController: Couchbase cluster metrics retrieved in " + (endTime - startTime) + "ms");
            // System.out.println("📊 Cluster: " + clusterMetrics.getClusterName() + " | Nodes: " + clusterMetrics.getNodes().size() + " | Buckets: " + clusterMetrics.getBuckets().size());
            
            return ResponseEntity.ok(clusterMetrics);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            System.out.println("❌ DashboardMetricsController: Failed to get cluster metrics after " + (endTime - startTime) + "ms: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthStatus() {
        try {
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            Map<String, Object> healthResponse = new HashMap<>();
            healthResponse.put("status", metrics.getHealth().getStatus());
            healthResponse.put("details", metrics.getHealth().getDetails());
            healthResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(healthResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemMetrics() {
        try {
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            Map<String, Object> systemResponse = new HashMap<>();
            systemResponse.put("systemMetrics", metrics.getSystemMetrics());
            systemResponse.put("jvmMetrics", metrics.getJvmMetrics());
            systemResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(systemResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/application")
    public ResponseEntity<Map<String, Object>> getApplicationMetrics() {
        try {
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            Map<String, Object> appResponse = new HashMap<>();
            appResponse.put("applicationInfo", metrics.getApplicationInfo());
            appResponse.put("applicationMetrics", metrics.getApplicationMetrics());
            appResponse.put("uptime", metrics.getUptime());
            appResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(appResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshMetrics() {
        try {
            // Clear cache and get fresh metrics
            actuatorAggregatorService.getAggregatedMetrics();
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Metrics refreshed successfully");
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to refresh metrics: " + e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        try {
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            Map<String, Object> summary = new HashMap<>();
            summary.put("health", metrics.getHealth().getStatus());
            summary.put("uptime", metrics.getUptime());
            summary.put("timestamp", metrics.getTimestamp());
            
            // Add key system metrics
            if (metrics.getSystemMetrics() != null) {
                summary.put("cpu", metrics.getSystemMetrics().get("cpu"));
                summary.put("memory", metrics.getSystemMetrics().get("memory"));
            }
            
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/fhir-metrics")
    public ResponseEntity<DashboardMetrics> getFhirMetrics() {
        try {
            // System.out.println("🚀 DashboardMetricsController: Getting FHIR application metrics");
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            // System.out.println("✅ FHIR application metrics retrieved successfully");
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            // System.out.println("❌ Failed to get FHIR metrics: " + e.getMessage());
            DashboardMetrics errorMetrics = new DashboardMetrics();
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("error", "Failed to fetch FHIR metrics: " + e.getMessage());
            errorMetrics.setApplicationInfo(errorInfo);
            return ResponseEntity.ok(errorMetrics);
        }
    }
}
