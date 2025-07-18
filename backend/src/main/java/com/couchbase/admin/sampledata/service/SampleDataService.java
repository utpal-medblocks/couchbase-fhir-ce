package com.couchbase.admin.sampledata.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.sampledata.model.SampleDataRequest;
import com.couchbase.admin.sampledata.model.SampleDataResponse;
import com.couchbase.admin.sampledata.model.SampleDataProgress;
import com.couchbase.fhir.resources.service.FHIRBundleProcessingService;
import com.couchbase.client.java.Cluster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service for loading sample FHIR data from embedded resources
 */
@Service
public class SampleDataService {
    
    private static final Logger log = LoggerFactory.getLogger(SampleDataService.class);
    private static final String SAMPLE_DATA_ZIP_PATH = "static/sample-data/synthea-patients-sample.zip";
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FHIRBundleProcessingService bundleProcessor;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Check if a ZIP entry should be processed (filters out macOS metadata files)
     */
    private boolean shouldProcessEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        
        String name = entry.getName();
        
        // Must be a JSON file
        if (!name.endsWith(".json")) {
            return false;
        }
        
        // Filter out macOS metadata files
        if (name.contains("__MACOSX") || 
            name.contains(".DS_Store") || 
            name.startsWith("._") ||
            name.contains("/._")) { // Also check for nested ._* files
            return false;
        }
        
        return true;
    }
    
    /**
     * Load sample FHIR data into the specified bucket
     */
    public SampleDataResponse loadSampleData(SampleDataRequest request) {
        log.info("Loading sample data for connection: {}, bucket: {}", 
                request.getConnectionName(), request.getBucketName());
        
        try {
            // Get connection
            Cluster cluster = connectionService.getConnection(request.getConnectionName());
            if (cluster == null) {
                return new SampleDataResponse(false, "Connection not found: " + request.getConnectionName());
            }
            
            // Load and process sample data
            int resourcesLoaded = 0;
            int patientsLoaded = 0;
            
            // First pass: count total files for progress tracking
            int totalFiles = 0;
            try (InputStream zipStream = new ClassPathResource(SAMPLE_DATA_ZIP_PATH).getInputStream();
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        totalFiles++;
                    }
                }
            }
            
            // Second pass: process files with progress tracking
            int processedFiles = 0;
            try (InputStream zipStream = new ClassPathResource(SAMPLE_DATA_ZIP_PATH).getInputStream();
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        String fileName = entry.getName();
                        log.info("Processing file {}/{}: {}", processedFiles + 1, totalFiles, fileName);
                        
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; // Use a reasonable buffer size
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        // Process the bundle using our sophisticated Bundle processor
                        String bundleJson = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                        Map<String, Integer> resourceCounts = processBundle(bundleJson, request.getConnectionName(), request.getBucketName());
                        resourcesLoaded += resourceCounts.getOrDefault("resources", 0);
                        patientsLoaded += resourceCounts.getOrDefault("patients", 0);
                        
                        processedFiles++;
                        double progress = (double) processedFiles / totalFiles * 100;
                        log.info("Progress: {:.1f}% ({} resources, {} patients loaded so far)", 
                                progress, resourcesLoaded, patientsLoaded);
                    }
                }
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data loaded successfully", 
                resourcesLoaded, 
                patientsLoaded
            );
            response.setBucketName(request.getBucketName());
            response.setConnectionName(request.getConnectionName());
            
            log.info("Successfully loaded {} resources ({} patients) into bucket: {}", 
                    resourcesLoaded, patientsLoaded, request.getBucketName());
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to load sample data: {}", e.getMessage(), e);
            return new SampleDataResponse(false, "Failed to load sample data: " + e.getMessage());
        }
    }
    
    /**
     * Check if sample data is available
     */
    public SampleDataResponse checkSampleDataAvailability() {
        try {
            ClassPathResource resource = new ClassPathResource(SAMPLE_DATA_ZIP_PATH);
            if (resource.exists()) {
                return new SampleDataResponse(true, "Sample data is available");
            } else {
                return new SampleDataResponse(false, "Sample data file not found");
            }
        } catch (Exception e) {
            log.error("Error checking sample data availability: {}", e.getMessage());
            return new SampleDataResponse(false, "Error checking sample data: " + e.getMessage());
        }
    }
    
    /**
     * Process a FHIR bundle using our sophisticated Bundle processor
     */
    private Map<String, Integer> processBundle(String bundleJson, String connectionName, String bucketName) {
        Map<String, Integer> counts = new HashMap<>();
        int resourceCount = 0;
        int patientCount = 0;
        
        try {
            // Use our sophisticated Bundle processor that handles:
            // - UUID reference resolution
            // - FHIR validation
            // - Sequential processing
            // - Proper document keys (resourceType/id)
            // - Audit trails
            Bundle responseBundle = bundleProcessor.processBundleTransaction(bundleJson, connectionName, bucketName);
            
            // Count successful entries from the response
            if (responseBundle != null && responseBundle.getEntry() != null) {
                for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
                    if (entry.getResponse() != null && entry.getResponse().getStatus() != null) {
                        String status = entry.getResponse().getStatus();
                        if (status.startsWith("201") || status.startsWith("200")) { // Created or OK
                            resourceCount++;
                            
                            // Count patients specifically
                            if (entry.getResource() != null && "Patient".equals(entry.getResource().getResourceType().name())) {
                                patientCount++;
                            }
                        }
                    }
                }
            }
            
            log.debug("Processed bundle with {} resources ({} patients) using Bundle processor", 
                    resourceCount, patientCount);
            
        } catch (Exception e) {
            log.error("Error processing FHIR bundle with Bundle processor: {}", e.getMessage());
            // Continue processing other bundles even if one fails
        }
        
        counts.put("resources", resourceCount);
        counts.put("patients", patientCount);
        return counts;
    }
    
    /**
     * Get sample data statistics (without loading)
     */
    public SampleDataResponse getSampleDataStats() {
        try {
            int totalResources = 0;
            int totalPatients = 0;
            
            try (InputStream zipStream = new ClassPathResource(SAMPLE_DATA_ZIP_PATH).getInputStream();
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        // Parse the bundle to count resources
                        JsonNode bundle = objectMapper.readTree(new ByteArrayInputStream(jsonBytes));
                        if (bundle.has("entry")) {
                            JsonNode entries = bundle.get("entry");
                            if (entries != null && entries.isArray()) {
                                for (JsonNode entryNode : entries) {
                                    JsonNode resource = entryNode.get("resource");
                                    if (resource != null) {
                                        totalResources++;
                                        String resourceType = resource.get("resourceType").asText();
                                        if ("Patient".equals(resourceType)) {
                                            totalPatients++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data contains " + totalPatients + " patients with " + totalResources + " total resources"
            );
            response.setResourcesLoaded(totalResources);
            response.setPatientsLoaded(totalPatients);
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to get sample data stats: {}", e.getMessage());
            return new SampleDataResponse(false, "Failed to analyze sample data: " + e.getMessage());
        }
    }
    
    /**
     * Load sample data with progress tracking capability
     * This method can be extended to support real-time progress updates via WebSocket or Server-Sent Events
     */
    public SampleDataResponse loadSampleDataWithProgress(SampleDataRequest request, ProgressCallback callback) {
        log.info("Loading sample data with progress tracking for connection: {}, bucket: {}", 
                request.getConnectionName(), request.getBucketName());
        
        try {
            // Get connection
            Cluster cluster = connectionService.getConnection(request.getConnectionName());
            if (cluster == null) {
                return new SampleDataResponse(false, "Connection not found: " + request.getConnectionName());
            }
            
            // Load and process sample data with progress callbacks
            int resourcesLoaded = 0;
            int patientsLoaded = 0;
            
            // First pass: count total files
            int totalFiles = 0;
            try (InputStream zipStream = new ClassPathResource(SAMPLE_DATA_ZIP_PATH).getInputStream();
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        totalFiles++;
                    }
                }
            }
            
            // Notify start
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress(totalFiles, 0, "Starting...");
                progress.setStatus("INITIATED");
                progress.setMessage("Initializing sample data loading...");
                callback.onProgress(progress);
            }
            
            // Second pass: process files
            int processedFiles = 0;
            try (InputStream zipStream = new ClassPathResource(SAMPLE_DATA_ZIP_PATH).getInputStream();
                 ZipInputStream zis = new ZipInputStream(zipStream)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (shouldProcessEntry(entry)) {
                        String fileName = entry.getName();
                        
                        // Update progress - BEFORE processing file
                        if (callback != null) {
                            SampleDataProgress progress = new SampleDataProgress(totalFiles, processedFiles, fileName);
                            progress.setStatus("IN_PROGRESS");
                            progress.setMessage("Processing " + fileName + "...");
                            progress.setResourcesLoaded(resourcesLoaded);
                            progress.setPatientsLoaded(patientsLoaded);
                            callback.onProgress(progress);
                        }
                        
                        // Read the JSON content using ByteArrayOutputStream for dynamic sizing
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] jsonBytes = baos.toByteArray();
                        
                        // Process the bundle using our sophisticated Bundle processor
                        String bundleJson = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                        Map<String, Integer> resourceCounts = processBundle(bundleJson, request.getConnectionName(), request.getBucketName());
                        resourcesLoaded += resourceCounts.getOrDefault("resources", 0);
                        patientsLoaded += resourceCounts.getOrDefault("patients", 0);
                        
                        processedFiles++;
                        
                        // Update progress - AFTER processing file
                        if (callback != null) {
                            SampleDataProgress progress = new SampleDataProgress(totalFiles, processedFiles, fileName);
                            progress.setStatus("IN_PROGRESS");
                            progress.setMessage("Completed " + fileName + " - " + resourcesLoaded + " resources loaded");
                            progress.setResourcesLoaded(resourcesLoaded);
                            progress.setPatientsLoaded(patientsLoaded);
                            callback.onProgress(progress);
                        }
                        
                        log.info("Processed file {}/{}: {} - {} resources, {} patients loaded so far", 
                                processedFiles, totalFiles, fileName, resourcesLoaded, patientsLoaded);
                    }
                }
            }
            
            // Final progress update
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress(totalFiles, processedFiles, "All files completed");
                progress.setStatus("COMPLETED");
                progress.setMessage("Sample data loading completed successfully - " + resourcesLoaded + " resources (" + patientsLoaded + " patients) loaded");
                progress.setResourcesLoaded(resourcesLoaded);
                progress.setPatientsLoaded(patientsLoaded);
                callback.onProgress(progress);
            }
            
            SampleDataResponse response = new SampleDataResponse(
                true, 
                "Sample data loaded successfully", 
                resourcesLoaded, 
                patientsLoaded
            );
            response.setBucketName(request.getBucketName());
            response.setConnectionName(request.getConnectionName());
            
            log.info("Successfully loaded {} resources ({} patients) into bucket: {}", 
                    resourcesLoaded, patientsLoaded, request.getBucketName());
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to load sample data: {}", e.getMessage(), e);
            
            // Notify error
            if (callback != null) {
                SampleDataProgress progress = new SampleDataProgress();
                progress.setStatus("ERROR");
                progress.setMessage("Failed to load sample data: " + e.getMessage());
                callback.onProgress(progress);
            }
            
            return new SampleDataResponse(false, "Failed to load sample data: " + e.getMessage());
        }
    }
    
    /**
     * Callback interface for progress updates
     */
    public interface ProgressCallback {
        void onProgress(SampleDataProgress progress);
    }
} 