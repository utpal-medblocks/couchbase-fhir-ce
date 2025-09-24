package com.couchbase.admin.metrics.service;

import com.couchbase.admin.metrics.model.MetricDataPoint;
import com.couchbase.admin.metrics.model.HaproxyMetricsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Service for collecting and managing HAProxy metrics in ring buffers
 */
@Service
public class HaproxyMetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(HaproxyMetricsService.class);
    
    // Ring buffer configurations
    private static final Map<String, Integer> STRIDE = Map.of(
        "minute", 1,      // Every 6s
        "hour", 10,       // Every 60s  
        "day", 240,       // Every 24min
        "week", 1680,     // Every 2.8h
        "month", 7200     // Every 12h
    );
    
    private static final Map<String, Integer> CAPACITY = Map.of(
        "minute", 10,
        "hour", 60,
        "day", 60,
        "week", 60,
        "month", 60
    );
    
    // Ring buffers
    private final Map<String, ConcurrentLinkedDeque<MetricDataPoint>> buffers = new HashMap<>();
    
    // State tracking
    private volatile int tick = 0;
    private volatile Map<String, Object> previousSnapshot = null;
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${server.port:8080}")
    private String serverPort;
    
    @Value("${haproxy.metrics.host:localhost}")
    private String metricsHost;
    
    @PostConstruct
    public void initialize() {
        logger.info("🚀 Initializing HAProxy Metrics Service");
        
        // Initialize ring buffers
        CAPACITY.keySet().forEach(key -> 
            buffers.put(key, new ConcurrentLinkedDeque<>())
        );
        
        // Get initial snapshot
        try {
            previousSnapshot = fetchHaproxySnapshot();
            logger.info("✅ Initial HAProxy snapshot acquired");
        } catch (Exception e) {
            logger.warn("⚠️ Failed to get initial HAProxy snapshot: {}", e.getMessage());
        }
    }
    
    @Scheduled(fixedRate = 6000) // Every 6 seconds
    public void collectMetrics() {
        try {
            Map<String, Object> currentSnapshot = fetchHaproxySnapshot();
            
            if (previousSnapshot != null && currentSnapshot != null) {
                MetricDataPoint dataPoint = calculateMetrics(currentSnapshot, previousSnapshot);
                
                // Add to appropriate buffers based on tick and stride
                STRIDE.forEach((timeRange, stride) -> {
                    ConcurrentLinkedDeque<MetricDataPoint> buffer = buffers.get(timeRange);
                    
                    // Always add to empty buffers OR when stride matches
                    if (buffer.isEmpty() || tick % stride == 0) {
                        buffer.addLast(dataPoint);
                        
                        // Maintain capacity
                        while (buffer.size() > CAPACITY.get(timeRange)) {
                            buffer.removeFirst();
                        }
                    }
                });
            }
            
            previousSnapshot = currentSnapshot;
            tick++;
            
        } catch (Exception e) {
            // logger.error("❌ Error collecting HAProxy metrics: {}", e.getMessage());
        }
    }
    
    @SuppressWarnings("rawtypes")
    private Map<String, Object> fetchHaproxySnapshot() throws Exception {
        String baseUrl = "http://" + metricsHost + ":" + serverPort;
        ResponseEntity<Map> response = restTemplate.getForEntity(
            baseUrl + "/api/dashboard/haproxy-metrics", Map.class);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> haproxy = (Map<String, Object>) body.get("haproxy");
            
            if (haproxy != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> services = (Map<String, Object>) haproxy.get("services");
                if (services != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> backendService = (Map<String, Object>) services.get("backend-fhir-server_backend");
                    return backendService;
                }
            }
        }
        
        throw new RuntimeException("Failed to fetch HAProxy snapshot");
    }
    
    private MetricDataPoint calculateMetrics(Map<String, Object> current, Map<String, Object> previous) {
        long timestamp = System.currentTimeMillis();
        
        // Calculate ops/sec from req_tot delta
        double ops = 0.0;
        try {
            Object currentReqTot = current.get("req_tot");
            Object previousReqTot = previous.get("req_tot");
            
            if (currentReqTot instanceof Number && previousReqTot instanceof Number) {
                long currentReq = ((Number) currentReqTot).longValue();
                long previousReq = ((Number) previousReqTot).longValue();
                ops = Math.max(0, (currentReq - previousReq) / 6.0);
            }
        } catch (Exception e) {
            logger.debug("Error calculating ops: {}", e.getMessage());
        }
        
        // Graph 1: Client Latency metrics (only if there's meaningful traffic)
        long currentReqTot = getLongValue(current, "req_tot");
        long previousReqTot = getLongValue(previous, "req_tot");
        long requestDelta = currentReqTot - previousReqTot;
        boolean hasMeaningfulTraffic = requestDelta >= 2; // Ignore health checks/admin polling
        
        double latency = hasMeaningfulTraffic ? getDoubleValue(current, "ttime") : 0.0;
        double latencyMax = hasMeaningfulTraffic ? getDoubleValue(current, "ttime_max") : 0.0;
        
        // Graph 2: Throughput & Concurrency metrics
        long scur = getLongValue(current, "scur");
        long rateMax = getLongValue(current, "rate_max");
        long smax = getLongValue(current, "smax");
        long qcur = getLongValue(current, "qcur");
        
        // Graph 3: System Resources (from ActuatorAggregatorService)
        SystemMetrics systemMetrics = getSystemMetrics();
        
        // Graph 4: Health/Error metrics
        long hrsp4xx = getLongValue(current, "hrsp_4xx");
        long hrsp5xx = getLongValue(current, "hrsp_5xx");
        long totalRequests = getLongValue(current, "req_tot");
        double errorPercent = calculateErrorPercent(hrsp4xx, hrsp5xx, totalRequests);
        
        return new MetricDataPoint(timestamp, ops, scur, rateMax, smax, qcur, latency, latencyMax,
                                   systemMetrics.cpu, systemMetrics.memory, systemMetrics.disk,
                                   hrsp4xx, hrsp5xx, errorPercent);
    }
    
    private double getDoubleValue(Map<String, Object> data, String key) {
        try {
            Object value = data.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            } else if (value instanceof String) {
                return Double.parseDouble((String) value);
            }
        } catch (Exception e) {
            logger.debug("Error parsing double value for {}: {}", key, e.getMessage());
        }
        return 0.0;
    }
    
    private long getLongValue(Map<String, Object> data, String key) {
        try {
            Object value = data.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            } else if (value instanceof String) {
                return Long.parseLong((String) value);
            }
        } catch (Exception e) {
            logger.debug("Error parsing long value for {}: {}", key, e.getMessage());
        }
        return 0L;
    }
    
    private SystemMetrics getSystemMetrics() {
        try {
            return collectJvmMetrics();
        } catch (Exception e) {
            logger.debug("Error getting system metrics: {}", e.getMessage());
            return new SystemMetrics(0.0, 0.0, 0.0);
        }
    }
    
    private SystemMetrics collectJvmMetrics() {
        try {
            // Get CPU usage
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double cpuUsage = 0.0;
            
            // Try to get process CPU usage (available in some JVM implementations)
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                cpuUsage = sunOsBean.getProcessCpuLoad() * 100.0;
                if (cpuUsage < 0) cpuUsage = 0.0; // Handle -1 return value
            }
            
            // Get memory usage
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double memoryUsage = ((double) heapUsage.getUsed() / heapUsage.getMax()) * 100.0;
            
            // Get disk usage (simple approximation)
            double diskUsage = getDiskUsage();
            
            return new SystemMetrics(cpuUsage, memoryUsage, diskUsage);
            
        } catch (Exception e) {
            logger.debug("Error collecting JVM metrics: {}", e.getMessage());
            return new SystemMetrics(0.0, 0.0, 0.0);
        }
    }
    
    private double getDiskUsage() {
        try {
            File root = new File("/");
            long totalSpace = root.getTotalSpace();
            long freeSpace = root.getFreeSpace();
            long usedSpace = totalSpace - freeSpace;
            
            if (totalSpace > 0) {
                return ((double) usedSpace / totalSpace) * 100.0;
            }
        } catch (Exception e) {
            logger.debug("Error getting disk usage: {}", e.getMessage());
        }
        return 0.0;
    }
    
    private double calculateErrorPercent(long hrsp4xx, long hrsp5xx, long totalRequests) {
        if (totalRequests <= 0) return 0.0;
        return ((double) (hrsp4xx + hrsp5xx) / totalRequests) * 100.0;
    }
    
    // Inner class for system metrics
    private static class SystemMetrics {
        final double cpu;
        final double memory; 
        final double disk;
        
        SystemMetrics(double cpu, double memory, double disk) {
            this.cpu = cpu;
            this.memory = memory;
            this.disk = disk;
        }
    }
    
    public HaproxyMetricsResponse getMetrics() {
        HaproxyMetricsResponse response = new HaproxyMetricsResponse();
        
        // Copy current buffer contents
        response.setMinute(new ArrayList<>(buffers.get("minute")));
        response.setHour(new ArrayList<>(buffers.get("hour")));
        response.setDay(new ArrayList<>(buffers.get("day")));
        response.setWeek(new ArrayList<>(buffers.get("week")));
        response.setMonth(new ArrayList<>(buffers.get("month")));
        
        // Add current snapshot
        response.setCurrent(previousSnapshot);
        response.setTimestamp(System.currentTimeMillis());
        
        return response;
    }
}
