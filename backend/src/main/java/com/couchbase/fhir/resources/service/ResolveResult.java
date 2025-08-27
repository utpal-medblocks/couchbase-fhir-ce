package com.couchbase.fhir.resources.service;

/**
 * Result of conditional operation resolution for FHIR resources.
 * Used to determine if a conditional operation (DELETE, PUT, PATCH) should proceed.
 */
public class ResolveResult {
    
    public enum Status {
        ZERO,  // No matching resources found
        ONE,   // Exactly one matching resource found
        MANY   // Multiple matching resources found (ambiguous)
    }
    
    private final Status status;
    private final String resourceId;  // Only set when status == ONE
    
    private ResolveResult(Status status, String resourceId) {
        this.status = status;
        this.resourceId = resourceId;
    }
    
    // Factory methods
    public static ResolveResult zero() {
        return new ResolveResult(Status.ZERO, null);
    }
    
    public static ResolveResult one(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource ID cannot be null or empty for ONE result");
        }
        return new ResolveResult(Status.ONE, resourceId.trim());
    }
    
    public static ResolveResult many() {
        return new ResolveResult(Status.MANY, null);
    }
    
    // Getters
    public Status getStatus() {
        return status;
    }
    
    public String getResourceId() {
        if (status != Status.ONE) {
            throw new IllegalStateException("Resource ID is only available when status is ONE, current status: " + status);
        }
        return resourceId;
    }
    
    // Convenience methods
    public boolean isZero() {
        return status == Status.ZERO;
    }
    
    public boolean isOne() {
        return status == Status.ONE;
    }
    
    public boolean isMany() {
        return status == Status.MANY;
    }
    
    @Override
    public String toString() {
        return switch (status) {
            case ZERO -> "ResolveResult{ZERO}";
            case ONE -> "ResolveResult{ONE, resourceId='" + resourceId + "'}";
            case MANY -> "ResolveResult{MANY}";
        };
    }
}
