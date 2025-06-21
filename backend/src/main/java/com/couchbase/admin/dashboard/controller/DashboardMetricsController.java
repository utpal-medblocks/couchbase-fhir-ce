package com.couchbase.admin.dashboard.controller;

import com.couchbase.admin.dashboard.model.DashboardMetrics;
import com.couchbase.admin.dashboard.service.ActuatorAggregatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardMetricsController {

    @Autowired
    private ActuatorAggregatorService actuatorAggregatorService;

    @GetMapping("/metrics")
    @Cacheable(value = "dashboardMetrics", unless = "#result.body == null")
    public ResponseEntity<DashboardMetrics> getDashboardMetrics() {
        try {
            DashboardMetrics metrics = actuatorAggregatorService.getAggregatedMetrics();
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            // Return partial metrics with error information
            DashboardMetrics errorMetrics = new DashboardMetrics();
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("error", "Failed to fetch metrics: " + e.getMessage());
            errorMetrics.setApplicationInfo(errorInfo);
            return ResponseEntity.ok(errorMetrics);
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
}
