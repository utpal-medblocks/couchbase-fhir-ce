package com.couchbase.admin.dashboard.service;

import com.couchbase.admin.dashboard.model.DashboardMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;

@Service
public class ActuatorAggregatorService {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private InfoEndpoint infoEndpoint;

    @Autowired
    private MetricsEndpoint metricsEndpoint;

    @Autowired
    private MeterRegistry meterRegistry;

    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    public DashboardMetrics getAggregatedMetrics() {
        DashboardMetrics metrics = new DashboardMetrics();

        // Health Status
        try {
            var healthComponent = healthEndpoint.health();
            // Create a simple health object from the component
            Health health = Health.status(healthComponent.getStatus()).build();
            metrics.setHealth(health);
        } catch (Exception e) {
            metrics.setHealth(Health.down().withException(e).build());
        }

        // Application Info
        metrics.setApplicationInfo(infoEndpoint.info());

        // System Metrics
        metrics.setSystemMetrics(getSystemMetrics());

        // JVM Metrics
        metrics.setJvmMetrics(getJvmMetrics());

        // Custom Application Metrics
        metrics.setApplicationMetrics(getApplicationMetrics());

        // Uptime
        metrics.setUptime(getUptime());

        return metrics;
    }

    private Map<String, Object> getSystemMetrics() {
        Map<String, Object> systemMetrics = new HashMap<>();

        try {
            // CPU Usage
            Double cpuUsage = getMetricValue("system.cpu.usage");
            systemMetrics.put("cpu.usage.percent", cpuUsage != null ? cpuUsage * 100 : 0);

            // System Load Average
            Double loadAverage = getMetricValue("system.load.average.1m");
            systemMetrics.put("load.average.1m", loadAverage);

            // Available Processors
            systemMetrics.put("cpu.count", Runtime.getRuntime().availableProcessors());

            // Disk Space
            Double diskFree = getMetricValue("disk.free");
            Double diskTotal = getMetricValue("disk.total");
            systemMetrics.put("disk.free.bytes", diskFree);
            systemMetrics.put("disk.total.bytes", diskTotal);
            if (diskFree != null && diskTotal != null && diskTotal > 0) {
                systemMetrics.put("disk.usage.percent", ((diskTotal - diskFree) / diskTotal) * 100);
            }

        } catch (Exception e) {
            systemMetrics.put("error", "Unable to fetch system metrics: " + e.getMessage());
        }

        return systemMetrics;
    }

    private Map<String, Object> getJvmMetrics() {
        Map<String, Object> jvmMetrics = new HashMap<>();

        try {
            // Memory Usage
            long usedMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
            long maxMemory = memoryMXBean.getHeapMemoryUsage().getMax();
            long committedMemory = memoryMXBean.getHeapMemoryUsage().getCommitted();

            jvmMetrics.put("memory.used.bytes", usedMemory);
            jvmMetrics.put("memory.max.bytes", maxMemory);
            jvmMetrics.put("memory.committed.bytes", committedMemory);
            jvmMetrics.put("memory.usage.percent", maxMemory > 0 ? (double) usedMemory / maxMemory * 100 : 0);

            // Non-Heap Memory
            long nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage().getUsed();
            long nonHeapMax = memoryMXBean.getNonHeapMemoryUsage().getMax();
            jvmMetrics.put("memory.nonheap.used.bytes", nonHeapUsed);
            jvmMetrics.put("memory.nonheap.max.bytes", nonHeapMax);

            // JVM Threads
            Double threadCount = getMetricValue("jvm.threads.live");
            jvmMetrics.put("threads.live", threadCount);

            Double peakThreadCount = getMetricValue("jvm.threads.peak");
            jvmMetrics.put("threads.peak", peakThreadCount);

            // Garbage Collection
            Double gcPausesTotalTime = getMetricValue("jvm.gc.pause", "cause", "end.of.major.GC");
            jvmMetrics.put("gc.pause.total.time", gcPausesTotalTime);

        } catch (Exception e) {
            jvmMetrics.put("error", "Unable to fetch JVM metrics: " + e.getMessage());
        }

        return jvmMetrics;
    }

    private Map<String, Object> getApplicationMetrics() {
        Map<String, Object> appMetrics = new HashMap<>();

        try {
            // HTTP Request Metrics
            Double httpRequestsTotal = getMetricValue("http.server.requests");
            appMetrics.put("http.requests.total", httpRequestsTotal);

            // Active HTTP connections
            Double httpConnectionsActive = getMetricValue("tomcat.sessions.active.current");
            appMetrics.put("http.connections.active", httpConnectionsActive);

            // Database connections (if using connection pooling)
            Double dbConnectionsActive = getMetricValue("hikaricp.connections.active");
            appMetrics.put("database.connections.active", dbConnectionsActive);

            Double dbConnectionsIdle = getMetricValue("hikaricp.connections.idle");
            appMetrics.put("database.connections.idle", dbConnectionsIdle);

            // Couchbase specific metrics (if available)
            Double couchbaseConnections = getMetricValue("couchbase.connections.open");
            appMetrics.put("couchbase.connections.open", couchbaseConnections);

        } catch (Exception e) {
            appMetrics.put("error", "Unable to fetch application metrics: " + e.getMessage());
        }

        return appMetrics;
    }

    private String getUptime() {
        long uptimeMillis = runtimeMXBean.getUptime();
        long uptimeSeconds = uptimeMillis / 1000;
        
        long days = uptimeSeconds / (24 * 3600);
        long hours = (uptimeSeconds % (24 * 3600)) / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;

        return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
    }

    private Double getMetricValue(String metricName) {
        try {
            var response = metricsEndpoint.metric(metricName, null);
            if (response != null && !response.getMeasurements().isEmpty()) {
                return response.getMeasurements().get(0).getValue();
            }
        } catch (Exception e) {
            // Metric not available or error occurred
        }
        return null;
    }

    private Double getMetricValue(String metricName, String tagKey, String tagValue) {
        try {
            var tags = java.util.List.of(tagKey + ":" + tagValue);
            var response = metricsEndpoint.metric(metricName, tags);
            if (response != null && !response.getMeasurements().isEmpty()) {
                return response.getMeasurements().get(0).getValue();
            }
        } catch (Exception e) {
            // Metric not available or error occurred
        }
        return null;
    }

    // Method to create custom counters for FHIR operations
    public void incrementFhirOperationCounter(String operation, String resourceType) {
        Counter.builder("fhir.operations")
                .tag("operation", operation)
                .tag("resource.type", resourceType)
                .register(meterRegistry)
                .increment();
    }

    // Method to record response time for FHIR operations
    public void recordFhirOperationTimer(String operation, String resourceType, long milliseconds) {
        meterRegistry.timer("fhir.operation.duration", 
                "operation", operation, 
                "resource.type", resourceType)
                .record(java.time.Duration.ofMillis(milliseconds));
    }
}
