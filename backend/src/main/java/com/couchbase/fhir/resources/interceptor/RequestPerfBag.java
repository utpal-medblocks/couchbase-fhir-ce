package com.couchbase.fhir.resources.interceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-request performance tracking bag that accumulates timing data and metrics
 * as a request flows through different layers of the FHIR system.
 */
public class RequestPerfBag {
    
    private final String requestId;
    private final long startNs;
    private final Map<String, Long> timings;
    private final Map<String, Integer> counts;
    
    // Request metadata
    private String operation;
    private String resource;
    private String bucket;
    private String status = "unknown";
    
    public RequestPerfBag() {
        this.requestId = UUID.randomUUID().toString().substring(0, 8); // Short ID for logs
        this.startNs = System.nanoTime();
        this.timings = new LinkedHashMap<>(); // Preserve insertion order
        this.counts = new LinkedHashMap<>();  // Preserve insertion order
    }
    
    /**
     * Add a timing measurement in milliseconds (accumulates if key already exists)
     */
    public void addTiming(String name, long durationMs) {
        if (name != null && durationMs >= 0) {
            timings.put(name, timings.getOrDefault(name, 0L) + durationMs);
        }
    }
    
    /**
     * Add a count metric (accumulates if key already exists)
     */
    public void addCount(String name, int count) {
        if (name != null && count >= 0) {
            counts.put(name, counts.getOrDefault(name, 0) + count);
        }
    }
    
    /**
     * Increment a counter by 1 (convenience method)
     */
    public void incrementCount(String name) {
        addCount(name, 1);
    }
    
    /**
     * Set request metadata
     */
    public void setRequestInfo(String operation, String resource, String bucket) {
        this.operation = operation;
        this.resource = resource;
        this.bucket = bucket;
    }
    
    /**
     * Set final request status
     */
    public void setStatus(String status) {
        this.status = status != null ? status : "unknown";
    }
    
    /**
     * Get total request duration in milliseconds
     */
    public long getTotalDurationMs() {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
    
    /**
     * Get a summary string for logging (DEBUG level)
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("reqId=").append(requestId);
        sb.append(", total=").append(getTotalDurationMs()).append("ms");
        
        if (!timings.isEmpty()) {
            sb.append(", timings={");
            timings.forEach((key, value) -> sb.append(key).append("=").append(value).append("ms "));
            sb.append("}");
        }
        
        if (!counts.isEmpty()) {
            sb.append(", counts={");
            counts.forEach((key, value) -> sb.append(key).append("=").append(value).append(" "));
            sb.append("}");
        }
        
        return sb.toString();
    }
    
    /**
     * Get structured log line for INFO level (Loki/grep friendly)
     * Format: reqId=xxx method=GET path="..." duration_ms=123 status=success entries=10 bytes=1066
     */
    public String getStructuredLogLine(String method, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("reqId=").append(requestId);
        
        if (method != null) {
            sb.append(" method=").append(method);
        }
        
        if (path != null) {
            sb.append(" path=\"").append(path).append("\"");
        }
        
        sb.append(" duration_ms=").append(getTotalDurationMs());
        sb.append(" status=").append(status);
        
        // Add entries count if available (from search results or bundle)
        Integer entries = counts.get("entries");
        if (entries != null) {
            sb.append(" entries=").append(entries);
        }
        
        // Add bytes if available (response size)
        Integer bytes = counts.get("response_bytes");
        if (bytes != null) {
            sb.append(" bytes=").append(bytes);
        }
        
        // Add resource type and operation for context
        if (resource != null) {
            sb.append(" resource=").append(resource);
        }
        
        if (operation != null) {
            sb.append(" operation=").append(operation);
        }
        
        return sb.toString();
    }
    
    // Getters
    public String getRequestId() { return requestId; }
    public String getOperation() { return operation; }
    public String getResource() { return resource; }
    public String getBucket() { return bucket; }
    public String getStatus() { return status; }
    public Map<String, Long> getTimings() { return new LinkedHashMap<>(timings); }
    public Map<String, Integer> getCounts() { return new LinkedHashMap<>(counts); }
}
