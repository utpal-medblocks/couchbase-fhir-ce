package com.couchbase.admin.metrics.controller;

import com.couchbase.admin.metrics.model.HaproxyMetricsResponse;
import com.couchbase.admin.metrics.service.HaproxyMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for HAProxy time-series metrics
 */
@RestController
@RequestMapping("/api/metrics")
@CrossOrigin(origins = "*")
public class HaproxyMetricsController {
    
    @Autowired
    private HaproxyMetricsService haproxyMetricsService;
    
    @GetMapping("/haproxy-timeseries")
    public ResponseEntity<HaproxyMetricsResponse> getHaproxyTimeSeries() {
        try {
            HaproxyMetricsResponse metrics = haproxyMetricsService.getMetrics();
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
