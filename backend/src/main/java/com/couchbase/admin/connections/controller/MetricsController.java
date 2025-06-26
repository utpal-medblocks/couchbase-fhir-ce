package com.couchbase.admin.connections.controller;

import com.couchbase.admin.connections.model.ClusterMetrics;
import com.couchbase.admin.connections.service.ClusterMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
@CrossOrigin(origins = "*")
public class MetricsController {

    @Autowired
    private ClusterMetricsService clusterMetricsService;

    @GetMapping("/cluster/{connectionName}")
    public ResponseEntity<ClusterMetrics> getClusterMetrics(@PathVariable String connectionName) {
        try {
            ClusterMetrics metrics = clusterMetricsService.getClusterMetrics(connectionName);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
} 