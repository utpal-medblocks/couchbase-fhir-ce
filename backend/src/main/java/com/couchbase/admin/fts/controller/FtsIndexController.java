package com.couchbase.admin.fts.controller;

import com.couchbase.admin.fts.model.FtsIndex;
import com.couchbase.admin.fts.model.FtsIndexDetails;
import com.couchbase.admin.fts.model.FtsMetricsRequest;
import com.couchbase.admin.fts.model.FtsMetricsResponse;
import com.couchbase.admin.fts.service.FtsIndexService;
import com.couchbase.admin.fts.service.FtsMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for FTS index management and metrics
 */
@RestController
@RequestMapping("/api/fts")
@CrossOrigin(origins = "*")
public class FtsIndexController {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsIndexController.class);
    
    @Autowired
    private FtsIndexService ftsIndexService;

    @Autowired
    private FtsMetricsService ftsMetricsService;
    
    /**
     * Get all FTS index details for a specific bucket and scope
     */
    @GetMapping("/indexes")
    public ResponseEntity<List<FtsIndexDetails>> getFtsIndexDetails(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String scopeName) {
        try {
            logger.info("Getting FTS index details for connection: {}, bucket: {}, scope: {}", 
                connectionName, bucketName, scopeName);
            
            List<FtsIndexDetails> indexDetails = ftsIndexService.getFtsIndexDetails(connectionName, bucketName, scopeName);
            logger.info("Successfully retrieved {} FTS index details", indexDetails.size());
            
            return ResponseEntity.ok(indexDetails);
        } catch (Exception e) {
            logger.error("Failed to get FTS index details for {}/{}/{}: {}", 
                connectionName, bucketName, scopeName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get FTS index definitions only (without stats)
     */
    @GetMapping("/definitions")
    public ResponseEntity<List<FtsIndex>> getFtsIndexDefinitions(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String scopeName) {
        try {
            logger.info("Getting FTS index definitions for connection: {}, bucket: {}, scope: {}", 
                connectionName, bucketName, scopeName);
            
            List<FtsIndex> indexes = ftsIndexService.getFtsIndexDefinitions(connectionName, bucketName, scopeName);
            return ResponseEntity.ok(indexes);
        } catch (Exception e) {
            logger.error("Failed to get FTS index definitions: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get FTS index statistics only
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getFtsIndexStats(@RequestParam String connectionName) {
        try {
            logger.info("Getting FTS index stats for connection: {}", connectionName);
            
            var stats = ftsIndexService.getFtsIndexStats(connectionName);
            return ResponseEntity.ok(Map.of("stats", stats));
        } catch (Exception e) {
            logger.error("Failed to get FTS index stats: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get FTS index time-series metrics for graphing
     */
    @GetMapping("/metrics")
    public ResponseEntity<FtsMetricsResponse> getFtsMetrics(
            @RequestParam String connectionName,
            @RequestParam String bucketName,
            @RequestParam String indexName,
            @RequestParam(defaultValue = "HOUR") String timeRange) {
        
        logger.info("=== FTS METRICS ENDPOINT HIT ===");
        logger.info("Getting FTS metrics for connection: {}, bucket: {}, index: {}, timeRange: {}", 
                   connectionName, bucketName, indexName, timeRange);
        
        try {
            FtsMetricsRequest.TimeRange range = FtsMetricsRequest.TimeRange.valueOf(timeRange.toUpperCase());
            FtsMetricsResponse metrics = ftsMetricsService.getFtsMetrics(connectionName, bucketName, indexName, range);
            logger.info("Successfully retrieved FTS metrics for index: {}", indexName);
            return ResponseEntity.ok(metrics);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid time range: {}", timeRange);
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting FTS metrics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}