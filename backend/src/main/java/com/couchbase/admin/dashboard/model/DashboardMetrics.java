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
}
