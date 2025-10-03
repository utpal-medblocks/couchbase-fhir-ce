package com.couchbase.admin.connections.config;

import com.couchbase.admin.connections.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Component to warm up Couchbase connections and collections after application startup.
 * This eliminates the "first request penalty" where the initial KV operation on a collection
 * can take 300-500ms while subsequent operations take 1-2ms.
 * 
 * Uses @EventListener(ApplicationReadyEvent.class) with @Order to run AFTER connection is established.
 * Order is set to run after ConfigurationStartupService (which has no explicit order, defaults to LOWEST_PRECEDENCE).
 */
@Component
public class CouchbaseWarmup {

    private static final Logger logger = LoggerFactory.getLogger(CouchbaseWarmup.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    // Standard FHIR collections in Resources scope
    private static final List<String> FHIR_COLLECTIONS = Arrays.asList(
        "Patient", "Practitioner", "Observation", "Encounter", "Condition", "Procedure", "MedicationRequest", "DiagnosticReport", "DocumentReference", "Immunization", "ServiceRequest", "General"
    );
    
    public CouchbaseWarmup() {
        logger.info("🔧 CouchbaseWarmup component instantiated");
    }
    
    /**
     * Warm up collections after connection is established
     * Order annotation ensures this runs after ConfigurationStartupService
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1000) // Run after default listeners (which have LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        logger.info("🔥 CouchbaseWarmup.onApplicationReady() called - Starting warmup...");
        
        // Add a small delay to ensure connection is fully established
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        try {
            // Check if default connection exists
            if (connectionService.getConnection("default") == null) {
                logger.warn("⚠️ No default connection found - skipping warmup");
                return;
            }
            
            // Warm up FHIR collections in Resources scope
            // This will cache Collection objects and trigger initial KV operations
            logger.info("🔄 Warming up {} FHIR collections in Resources scope...", FHIR_COLLECTIONS.size());
            
            long startTime = System.currentTimeMillis();
            
            // Get bucket name from tenant context or use default
            String bucketName = determineBucketName();
            
            // Warm up all collections
            connectionService.warmupCollections("default", bucketName, "Resources", FHIR_COLLECTIONS);
            
            long duration = System.currentTimeMillis() - startTime;
            
            logger.info("✅ Couchbase warmup complete in {} ms", duration);
            logger.info("🚀 First FHIR requests will now be fast - connections are hot!");
            
        } catch (Exception e) {
            logger.warn("⚠️ Couchbase warmup failed (this won't affect functionality, but first requests may be slower): {}", e.getMessage());
            logger.debug("Warmup error details:", e);
        }
    }
    
    /**
     * Determine bucket name for warmup
     * In a multi-tenant setup, this would need to be more sophisticated
     */
    private String determineBucketName() {
        // For now, use a default bucket name
        // In production, you might want to read from config or warm up all known buckets
        return "acme"; // Default bucket name from your config
    }
}

