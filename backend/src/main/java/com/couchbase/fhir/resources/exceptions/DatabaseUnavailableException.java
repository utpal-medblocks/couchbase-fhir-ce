package com.couchbase.fhir.resources.exceptions;

/**
 * Exception thrown when the Couchbase database connection is unavailable.
 * This is mapped to HTTP 503 Service Unavailable, signaling to HAProxy
 * that this instance should be removed from the load balancer pool.
 */
public class DatabaseUnavailableException extends RuntimeException {
    
    public DatabaseUnavailableException(String message) {
        super(message);
    }
    
    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

