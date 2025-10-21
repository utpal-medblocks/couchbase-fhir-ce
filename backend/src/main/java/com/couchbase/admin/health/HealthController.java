package com.couchbase.admin.health;

import com.couchbase.fhir.resources.gateway.CouchbaseGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health and readiness endpoints for HAProxy/Kubernetes health checks.
 * 
 * HAProxy uses these endpoints to determine if this instance should receive traffic:
 * - /health/readiness -> 200 OK = add to pool, 503 = remove from pool
 * - /health/liveness -> 200 OK = keep running, 503 = restart container
 */
@RestController
@RequestMapping("/health")
public class HealthController {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    
    @Autowired
    private CouchbaseGateway couchbaseGateway;
    
    /**
     * Readiness probe - determines if instance should receive traffic.
     * Returns 503 if database is unavailable, causing HAProxy to route around this instance.
     * 
     * NOTE: This actively attempts a lightweight DB operation to test connectivity,
     * which helps close the circuit breaker faster when DB recovers.
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> response = new HashMap<>();
        
        boolean hasConnection = couchbaseGateway.hasConnection("default");
        boolean circuitOpen = couchbaseGateway.isCircuitOpen();
        
        response.put("timestamp", System.currentTimeMillis());
        response.put("database", hasConnection ? "UP" : "DOWN");
        response.put("circuitBreaker", circuitOpen ? "OPEN" : "CLOSED");
        
        // If circuit is open but we have a connection, try a lightweight test
        // This helps the circuit close faster when DB recovers
        if (hasConnection && circuitOpen) {
            try {
                // Attempt a very lightweight query to test if DB is actually back
                // This will either succeed (closing the circuit) or fail fast
                couchbaseGateway.query("default", "SELECT 1 LIMIT 1");
                // If we got here, circuit should have closed
                response.put("status", "READY");
                response.put("circuitBreaker", "CLOSED");
                logger.info("✅ Readiness check RECOVERED - circuit closed via health check");
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                // Still failing, circuit stays open
                response.put("status", "NOT_READY");
                response.put("lastError", e.getMessage());
                logger.debug("🔴 Readiness check - circuit still open: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
        }
        
        if (hasConnection && !circuitOpen) {
            response.put("status", "READY");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "NOT_READY");
            logger.warn("🔴 Readiness check FAILED - connection: {}, circuit: {}", 
                       hasConnection ? "exists" : "missing", 
                       circuitOpen ? "OPEN" : "CLOSED");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
    
    /**
     * Liveness probe - determines if container is healthy.
     * Only returns 503 for catastrophic failures that require restart.
     */
    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ALIVE");
        response.put("timestamp", System.currentTimeMillis());
        
        // Application is alive if it can respond to HTTP requests
        // Database down is NOT a liveness failure (just readiness)
        return ResponseEntity.ok(response);
    }
    
    /**
     * General health endpoint - detailed status information.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        
        boolean hasConnection = couchbaseGateway.hasConnection("default");
        boolean circuitOpen = couchbaseGateway.isCircuitOpen();
        
        response.put("timestamp", System.currentTimeMillis());
        response.put("application", "FHIR Server");
        response.put("version", "1.0.0");
        
        Map<String, Object> components = new HashMap<>();
        components.put("database", Map.of(
            "status", hasConnection ? "UP" : "DOWN",
            "type", "Couchbase"
        ));
        components.put("circuitBreaker", Map.of(
            "status", circuitOpen ? "OPEN" : "CLOSED"
        ));
        
        response.put("components", components);
        response.put("status", (hasConnection && !circuitOpen) ? "UP" : "DEGRADED");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Force circuit breaker reset - for emergency recovery.
     * POST /health/circuit/reset
     * 
     * Use this if the circuit is stuck open after DB recovery.
     */
    @org.springframework.web.bind.annotation.PostMapping("/circuit/reset")
    public ResponseEntity<Map<String, Object>> resetCircuit() {
        Map<String, Object> response = new HashMap<>();
        
        boolean wasOpen = couchbaseGateway.isCircuitOpen();
        
        try {
            // Try a lightweight query to test connectivity
            couchbaseGateway.query("default", "SELECT 1 LIMIT 1");
            
            response.put("success", true);
            response.put("message", "Circuit breaker reset successful - database is accessible");
            response.put("wasOpen", wasOpen);
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("✅ Manual circuit breaker reset successful");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Circuit breaker reset failed - database still unavailable");
            response.put("error", e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
            
            logger.warn("❌ Manual circuit breaker reset failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }
}

