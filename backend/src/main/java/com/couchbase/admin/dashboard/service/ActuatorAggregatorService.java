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


import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.File;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
public class ActuatorAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(ActuatorAggregatorService.class);

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

    /**
     * Returns a DashboardMetrics object with system, JVM, application, and FHIR metrics.
     *
     * If running inside a Docker container, merges Docker container stats and HAProxy stats
     * with actuator metrics for a complete and consistent view. Otherwise, returns only actuator metrics.
     *
     * - Docker stats: CPU/memory usage for all fhir-server containers (single snapshot, no streaming)
     * - HAProxy stats: status, totalOperations, currentOpsPerSec for fhir-server backend
     * - All other fields: from actuator metrics
     */
    public DashboardMetrics getAggregatedMetrics() {
        if (isRunningInContainer()) {
            //log.info("Detected running inside a container environment");
            return getMergedContainerAndActuatorMetrics();
        } else {
            //log.info("Detected running outside container (VM or bare metal)");
            return getActuatorOnlyMetrics();
        }
    }

    private boolean isRunningInContainer() {
        // Check both the environment variable and /.dockerenv file for robust detection
        String envValue = System.getenv("DEPLOYED_ENV");
        if (envValue != null && "container".equalsIgnoreCase(envValue.trim())) return true;
        try {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get("/.dockerenv"))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private DashboardMetrics getActuatorOnlyMetrics() {
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

        // FHIR Metrics (Mock data for now)
        metrics.setFhirMetrics(getFhirMetrics());

        // Uptime
        metrics.setUptime(getUptime());

        return metrics;
    }

    /**
     * Merges Docker and HAProxy stats with actuator metrics for a consistent response structure.
     */
    private DashboardMetrics getMergedContainerAndActuatorMetrics() {
        log.info("Merging Docker, HAProxy, and actuator metrics");
        DashboardMetrics metrics = getActuatorOnlyMetrics();
        Map<String, Object> systemMetrics = new HashMap<>(metrics.getSystemMetrics() != null ? metrics.getSystemMetrics() : Map.of());
        // Collect Docker stats
        log.info("Fetching Docker stats for fhir-server containers");
        DockerStatsResult dockerStats = collectDockerStats();
        log.info("Fetched Docker stats: {}", dockerStats.statsMap);
        if (dockerStats.dockerStatsFound) {
            systemMetrics.putAll(dockerStats.statsMap);
        }
        metrics.setSystemMetrics(systemMetrics);
        // Collect HAProxy stats and merge with FHIR metrics
        log.info("Fetching HAProxy stats from admin socket");
        Map<String, Object> fhirMetrics = new HashMap<>(metrics.getFhirMetrics() != null ? metrics.getFhirMetrics() : Map.of());
        Map<String, Object> server = new HashMap<>();
        if (fhirMetrics.get("server") instanceof Map) {
            server.putAll((Map<String, Object>) fhirMetrics.get("server"));
        }
        server.put("cpuUsage", dockerStats.totalCpu > 0 ? dockerStats.totalCpu : server.getOrDefault("cpuUsage", 0));
        server.put("memoryUsage", dockerStats.totalMem > 0 ? dockerStats.totalMem : server.getOrDefault("memoryUsage", 0));
        server.put("jvmThreads", server.getOrDefault("jvmThreads", 0));
        HaproxyStatsResult haproxyStats = collectHaproxyStats();
        log.info("Fetched HAProxy stats: status={}, totalOps={}, currentOpsPerSec={}, error={}", haproxyStats.status, haproxyStats.totalOperations, haproxyStats.currentOpsPerSec, haproxyStats.error);
        if (haproxyStats.status != null) {
            server.put("status", haproxyStats.status);
        }
        Map<String, Object> overall = fhirMetrics.containsKey("overall") ? (Map<String, Object>) fhirMetrics.get("overall") : new HashMap<>();
        if (haproxyStats.totalOperations != null) {
            overall.put("totalOperations", haproxyStats.totalOperations);
        }
        if (haproxyStats.currentOpsPerSec != null) {
            overall.put("currentOpsPerSec", haproxyStats.currentOpsPerSec);
        }
        if (haproxyStats.error != null) {
            server.put("haproxyError", haproxyStats.error);
        }
        fhirMetrics.put("server", server);
        fhirMetrics.put("overall", overall);
        metrics.setFhirMetrics(fhirMetrics);
        return metrics;
    }

    /**
     * Helper to collect Docker stats for all fhir-server containers.
     * Uses the Docker CLI instead of the Java client.
     * Returns a DockerStatsResult with total CPU, total memory, and per-container stats.
     */
    private DockerStatsResult collectDockerStats() {
        Map<String, Object> statsMap = new HashMap<>();
        double totalCpu = 0;
        double totalMem = 0;
        boolean dockerStatsFound = false;
        try {
            // Get container IDs/names for fhir-server containers
            Process psProc = new ProcessBuilder("docker", "ps", "--format", "{{.ID}} {{.Names}}").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(psProc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length < 2) continue;
                    String id = parts[0];
                    String name = parts[1];
                    if (!name.startsWith("fhir-server")) continue;

                    // Get stats for this container
                    Process statsProc = new ProcessBuilder(
                        "docker", "stats", "--no-stream", "--format",
                        "{{.CPUPerc}} {{.MemUsage}}", id
                    ).start();
                    try (BufferedReader statsReader = new BufferedReader(new InputStreamReader(statsProc.getInputStream()))) {
                        String statsLine = statsReader.readLine();
                        if (statsLine != null) {
                            dockerStatsFound = true;
                            String[] statsParts = statsLine.trim().split("\\s+", 2);
                            // CPU: e.g. "0.05%"
                            String cpuStr = statsParts[0].replace("%", "");
                            double cpu = parseDoubleSafe(cpuStr);
                            statsMap.put(id + ".cpu", cpu);
                            totalCpu += cpu;

                            // Memory: e.g. "12.34MiB / 1GiB"
                            String memUsage = statsParts.length > 1 ? statsParts[1] : "";
                            String[] memParts = memUsage.split("/");
                            if (memParts.length > 0) {
                                double mem = parseMemory(memParts[0].trim());
                                statsMap.put(id + ".memory", mem);
                                totalMem += mem;
                            }
                        }
                    } catch (Exception ex) {
                        log.error("Error reading docker stats for container {}: {}", id, ex.getMessage(), ex);
                        statsMap.put(id + ".error", ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Error fetching Docker stats via CLI: {}", ex.getMessage(), ex);
            statsMap.put("docker.error", ex.getMessage());
        }
        return new DockerStatsResult(statsMap, totalCpu, totalMem, dockerStatsFound);
    }

    // Helper to parse memory strings like "12.34MiB", "1.2GiB", "512KiB"
    private double parseMemory(String memStr) {
        Pattern pattern = Pattern.compile("([\\d.]+)([KMG]iB|B)");
        Matcher matcher = pattern.matcher(memStr);
        if (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "GiB": return value * 1024 * 1024 * 1024;
                case "MiB": return value * 1024 * 1024;
                case "KiB": return value * 1024;
                case "B":   return value;
            }
        }
        return 0;
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /**
     * Helper to collect HAProxy stats for the fhir-server backend.
     * Returns a HaproxyStatsResult with status, totalOperations, currentOpsPerSec, and error if any.
     * Uses junixsocket to query the HAProxy admin socket directly.
     */
    private HaproxyStatsResult collectHaproxyStats() {
        String status = null;
        Long totalOperations = null;
        Long currentOpsPerSec = null;
        String error = null;
        AFUNIXSocket socket = null;
        try {
            File socketFile = new File("/tmp/haproxy/haproxy.sock");
            socket = AFUNIXSocket.newInstance();
            socket.connect(new AFUNIXSocketAddress(socketFile));
            Writer writer = new OutputStreamWriter(socket.getOutputStream());
            writer.write("show stat\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String headerLine = reader.readLine(); // CSV header
            if (headerLine == null) throw new Exception("No data from HAProxy socket");
            String[] headers = headerLine.split(",");
            int pxnameIdx = -1, svnameIdx = -1, statusIdx = -1, stotIdx = -1, rateIdx = -1;
            for (int i = 0; i < headers.length; i++) {
                if ("# pxname".equals(headers[i])) pxnameIdx = i;
                else if ("svname".equals(headers[i])) svnameIdx = i;
                else if ("status".equals(headers[i])) statusIdx = i;
                else if ("stot".equals(headers[i])) stotIdx = i;
                else if ("rate".equals(headers[i])) rateIdx = i;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                if (fields.length <= Math.max(Math.max(pxnameIdx, svnameIdx), Math.max(statusIdx, Math.max(stotIdx, rateIdx)))) continue;
                if ("backend-fhir-server".equals(fields[pxnameIdx]) && "BACKEND".equals(fields[svnameIdx])) {
                    status = statusIdx >= 0 ? fields[statusIdx] : null;
                    totalOperations = stotIdx >= 0 ? parseLongSafe(fields[stotIdx]) : null;
                    currentOpsPerSec = rateIdx >= 0 ? parseLongSafe(fields[rateIdx]) : null;
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("Error fetching HAProxy stats: {}", ex.getMessage(), ex);
            error = ex.getMessage();
        } finally {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignore) {}
            }
        }
        return new HaproxyStatsResult(status, totalOperations, currentOpsPerSec, error);
    }

    private Long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }

    /**
     * Simple DTO for Docker stats aggregation.
     */
    private static class DockerStatsResult {
        final Map<String, Object> statsMap;
        final double totalCpu;
        final double totalMem;
        final boolean dockerStatsFound;
        DockerStatsResult(Map<String, Object> statsMap, double totalCpu, double totalMem, boolean dockerStatsFound) {
            this.statsMap = statsMap;
            this.totalCpu = totalCpu;
            this.totalMem = totalMem;
            this.dockerStatsFound = dockerStatsFound;
        }
    }

    /**
     * Simple DTO for HAProxy stats aggregation.
     */
    private static class HaproxyStatsResult {
        final String status;
        final Long totalOperations;
        final Long currentOpsPerSec;
        final String error;
        HaproxyStatsResult(String status, Long totalOperations, Long currentOpsPerSec, String error) {
            this.status = status;
            this.totalOperations = totalOperations;
            this.currentOpsPerSec = currentOpsPerSec;
            this.error = error;
        }
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

    // Generate mock FHIR metrics
    private Map<String, Object> getFhirMetrics() {
        Map<String, Object> fhirMetrics = new HashMap<>();
        
        // Server status (FHIR server specific, not system metrics)
        Map<String, Object> server = new HashMap<>();
        server.put("status", "UP");
        server.put("uptime", getUptime());
        
        // FHIR server should not include system metrics - those belong in systemMetrics
        // Only include FHIR-specific server info
        server.put("cpuUsage", 0); // Will be replaced with real FHIR server metrics later
        server.put("memoryUsage", 0); // Will be replaced with real FHIR server metrics later
        server.put("diskUsage", 0); // Will be replaced with real FHIR server metrics later
        server.put("jvmThreads", 0); // Will be replaced with real FHIR server metrics later
        
        fhirMetrics.put("server", server);
        
        // Version info (mock)
        Map<String, Object> version = new HashMap<>();
        version.put("fhirVersion", "R4");
        version.put("serverVersion", "1.0.0-SNAPSHOT");
        version.put("buildNumber", "build-2025.01.15-1234");
        fhirMetrics.put("version", version);
        
        // Operations (mock)
        Map<String, Object> operations = new HashMap<>();
        
        Map<String, Object> readOps = new HashMap<>();
        readOps.put("count", 756);
        readOps.put("avgLatency", 95);
        readOps.put("successRate", 99.2);
        operations.put("read", readOps);
        
        Map<String, Object> createOps = new HashMap<>();
        createOps.put("count", 234);
        createOps.put("avgLatency", 185);
        createOps.put("successRate", 98.5);
        operations.put("create", createOps);
        
        Map<String, Object> searchOps = new HashMap<>();
        searchOps.put("count", 289);
        searchOps.put("avgLatency", 145);
        searchOps.put("successRate", 96.8);
        operations.put("search", searchOps);
        
        fhirMetrics.put("operations", operations);
        
        // Overall performance (mock)
        Map<String, Object> overall = new HashMap<>();
        overall.put("totalOperations", 1279);
        overall.put("currentOpsPerSec", 2.8);
        overall.put("avgOpsPerSec", 2.3);
        fhirMetrics.put("overall", overall);
        
        return fhirMetrics;
    }
}
