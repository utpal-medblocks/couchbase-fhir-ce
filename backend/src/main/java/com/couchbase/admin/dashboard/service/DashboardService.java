package com.couchbase.admin.dashboard.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

@Service
public class DashboardService {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private MetricsEndpoint metricsEndpoint;

    public Map<String, Object> getDashboardMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // 1. Health Status
        metrics.put("health", getHealthStatus());

        // 2. CPU Usage %
        metrics.put("cpuUsage", getCpuUsage());

        // 3. Memory Usage %
        metrics.put("memoryUsage", getMemoryUsage());

        // 4. Uptime (formatted)
        metrics.put("uptime", getUptime());

        // 5. Disk Space %
        metrics.put("diskUsage", getDiskUsage());

        // Timestamp for when metrics were collected
        metrics.put("timestamp", System.currentTimeMillis());

        return metrics;
    }

    private String getHealthStatus() {
        try {
            return healthEndpoint.health().getStatus().toString();
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private Double getCpuUsage() {
        try {
            var response = metricsEndpoint.metric("system.cpu.usage", null);
            if (response != null && !response.getMeasurements().isEmpty()) {
                // Convert to percentage (micrometer returns 0-1, we want 0-100)
                return response.getMeasurements().get(0).getValue() * 100;
            }
        } catch (Exception e) {
            // CPU metric not available
        }
        return null;
    }

    private Double getMemoryUsage() {
        try {
            // Get used and max memory
            var usedResponse = metricsEndpoint.metric("jvm.memory.used", null);
            var maxResponse = metricsEndpoint.metric("jvm.memory.max", null);
            
            if (usedResponse != null && maxResponse != null && 
                !usedResponse.getMeasurements().isEmpty() && !maxResponse.getMeasurements().isEmpty()) {
                
                double used = usedResponse.getMeasurements().get(0).getValue();
                double max = maxResponse.getMeasurements().get(0).getValue();
                
                if (max > 0) {
                    return (used / max) * 100;
                }
            }
        } catch (Exception e) {
            // Memory metrics not available
        }
        return null;
    }

    private String getUptime() {
        try {
            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            return formatUptime(uptimeMillis);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String formatUptime(long uptimeMillis) {
        long uptimeSeconds = uptimeMillis / 1000;
        
        long days = uptimeSeconds / (24 * 3600);
        long hours = (uptimeSeconds % (24 * 3600)) / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private Double getDiskUsage() {
        try {
            var freeResponse = metricsEndpoint.metric("disk.free", null);
            var totalResponse = metricsEndpoint.metric("disk.total", null);
            
            if (freeResponse != null && totalResponse != null && 
                !freeResponse.getMeasurements().isEmpty() && !totalResponse.getMeasurements().isEmpty()) {
                
                double free = freeResponse.getMeasurements().get(0).getValue();
                double total = totalResponse.getMeasurements().get(0).getValue();
                
                if (total > 0) {
                    return ((total - free) / total) * 100;
                }
            }
        } catch (Exception e) {
            // Disk metrics not available
        }
        return null;
    }
}
