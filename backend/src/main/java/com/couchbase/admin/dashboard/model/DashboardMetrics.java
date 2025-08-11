package com.couchbase.admin.dashboard.model;

import lombok.Data;
import org.springframework.boot.actuate.health.Health;

import java.util.Map;

@Data
public class DashboardMetrics {
    private Health health;
    private Map<String, Object> applicationInfo;
    private Map<String, Object> systemMetrics;
    private Map<String, Object> jvmMetrics;
    private Map<String, Object> applicationMetrics;
    private Map<String, Object> fhirMetrics; // Added FHIR metrics
    private String uptime;
    private long timestamp;
    
    public DashboardMetrics() {
        this.timestamp = System.currentTimeMillis();
    }

    // Add missing getters and setters for all fields
    public org.springframework.boot.actuate.health.Health getHealth() { return health; }
    public void setHealth(org.springframework.boot.actuate.health.Health health) { this.health = health; }
    public Map<String, Object> getApplicationInfo() { return applicationInfo; }
    public void setApplicationInfo(Map<String, Object> applicationInfo) { this.applicationInfo = applicationInfo; }
    public Map<String, Object> getSystemMetrics() { return systemMetrics; }
    public void setSystemMetrics(Map<String, Object> systemMetrics) { this.systemMetrics = systemMetrics; }
    public Map<String, Object> getJvmMetrics() { return jvmMetrics; }
    public void setJvmMetrics(Map<String, Object> jvmMetrics) { this.jvmMetrics = jvmMetrics; }
    public Map<String, Object> getApplicationMetrics() { return applicationMetrics; }
    public void setApplicationMetrics(Map<String, Object> applicationMetrics) { this.applicationMetrics = applicationMetrics; }
    public Map<String, Object> getFhirMetrics() { return fhirMetrics; }
    public void setFhirMetrics(Map<String, Object> fhirMetrics) { this.fhirMetrics = fhirMetrics; }
    public String getUptime() { return uptime; }
    public void setUptime(String uptime) { this.uptime = uptime; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
