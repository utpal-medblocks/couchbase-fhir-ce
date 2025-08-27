package com.couchbase.admin.buckets.controller;

import com.couchbase.admin.buckets.model.BucketMetricsResponse;
import com.couchbase.admin.buckets.service.BucketMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/buckets")
public class BucketMetricsController {
    private static final Logger logger = LoggerFactory.getLogger(BucketMetricsController.class);

    @Autowired
    private BucketMetricsService bucketMetricsService;

    @GetMapping("/metrics")
    public ResponseEntity<BucketMetricsResponse> getBucketMetrics(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String timeRange) {
        
        try {
            // logger.info("Fetching bucket metrics for connection: {}, bucket: {}, timeRange: {}", 
            //            connectionName, bucketName, timeRange);
            
            BucketMetricsResponse response = bucketMetricsService.getBucketMetrics(connectionName, bucketName, timeRange);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error fetching bucket metrics", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
