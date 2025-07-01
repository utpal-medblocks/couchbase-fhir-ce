package com.couchbase.admin.sampledata.controller;

import com.couchbase.admin.sampledata.model.SampleDataRequest;
import com.couchbase.admin.sampledata.model.SampleDataResponse;
import com.couchbase.admin.sampledata.model.SampleDataProgress;
import com.couchbase.admin.sampledata.service.SampleDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for sample data management
 */
@RestController
@RequestMapping("/api/sample-data")
@CrossOrigin(origins = "*")
public class SampleDataController {
    
    private static final Logger logger = LoggerFactory.getLogger(SampleDataController.class);
    
    @Autowired
    private SampleDataService sampleDataService;
    
    /**
     * Load sample FHIR data into a bucket
     */
    @PostMapping("/load")
    public ResponseEntity<SampleDataResponse> loadSampleData(@RequestBody SampleDataRequest request) {
        try {
            logger.info("Loading sample data for connection: {}, bucket: {}", 
                    request.getConnectionName(), request.getBucketName());
            
            SampleDataResponse response = sampleDataService.loadSampleData(request);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            logger.error("Failed to load sample data: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new SampleDataResponse(false, "Failed to load sample data: " + e.getMessage()));
        }
    }
    
    /**
     * Check if sample data is available
     */
    @GetMapping("/availability")
    public ResponseEntity<SampleDataResponse> checkAvailability() {
        try {
            logger.debug("Checking sample data availability");
            SampleDataResponse response = sampleDataService.checkSampleDataAvailability();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to check sample data availability: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new SampleDataResponse(false, "Error checking availability: " + e.getMessage()));
        }
    }
    
    /**
     * Get sample data statistics without loading
     */
    @GetMapping("/stats")
    public ResponseEntity<SampleDataResponse> getSampleDataStats() {
        try {
            logger.debug("Getting sample data statistics");
            SampleDataResponse response = sampleDataService.getSampleDataStats();
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            logger.error("Failed to get sample data stats: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new SampleDataResponse(false, "Failed to get stats: " + e.getMessage()));
        }
    }
    
    /**
     * Health check endpoint for sample data feature
     */
    @GetMapping("/health")
    public ResponseEntity<SampleDataResponse> healthCheck() {
        try {
            SampleDataResponse availability = sampleDataService.checkSampleDataAvailability();
            if (availability.isSuccess()) {
                return ResponseEntity.ok(new SampleDataResponse(true, "Sample data feature is healthy"));
            } else {
                return ResponseEntity.ok(new SampleDataResponse(false, "Sample data not available"));
            }
        } catch (Exception e) {
            logger.error("Sample data health check failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new SampleDataResponse(false, "Health check failed: " + e.getMessage()));
        }
    }
    
    /**
     * Load sample data with real-time progress updates via Server-Sent Events
     */
    @PostMapping("/load-with-progress")
    public SseEmitter loadSampleDataWithProgress(@RequestBody SampleDataRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minute timeout
        
        logger.info("Starting sample data load with progress for connection: {}, bucket: {}", 
                request.getConnectionName(), request.getBucketName());
        
        // Process asynchronously to avoid blocking
        CompletableFuture.runAsync(() -> {
            try {
                sampleDataService.loadSampleDataWithProgress(request, (progress) -> {
                    try {
                        // Send progress update via SSE
                        emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(progress));
                        
                        // Complete the SSE stream when done
                        if ("COMPLETED".equals(progress.getStatus()) || "ERROR".equals(progress.getStatus())) {
                            emitter.complete();
                        }
                    } catch (Exception e) {
                        logger.error("Error sending progress update: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                });
            } catch (Exception e) {
                logger.error("Error during sample data loading: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(new SampleDataProgress() {{
                                setStatus("ERROR");
                                setMessage("Failed to load sample data: " + e.getMessage());
                            }}));
                } catch (Exception sendError) {
                    logger.error("Error sending error event: {}", sendError.getMessage());
                }
                emitter.completeWithError(e);
            }
        });
        
        // Handle client disconnect
        emitter.onCompletion(() -> logger.info("SSE completed for sample data loading"));
        emitter.onTimeout(() -> {
            logger.warn("SSE timeout for sample data loading");
            emitter.complete();
        });
        emitter.onError((ex) -> {
            logger.error("SSE error for sample data loading: {}", ex.getMessage());
            emitter.completeWithError(ex);
        });
        
        return emitter;
    }
} 