package com.couchbase.fhir.resources.service;

import org.hl7.fhir.r4.model.Bundle;
import lombok.Data;

@Data
public class ProcessedEntry {
    private boolean success;
    private String resourceType;
    private String resourceId;
    private String documentKey;
    private Bundle.BundleEntryComponent responseEntry;
    private String errorMessage;

    // No-arg constructor for serialization/deserialization
    public ProcessedEntry() {
    }
    
    public ProcessedEntry(boolean success, String resourceType, String resourceId, String documentKey, Bundle.BundleEntryComponent responseEntry, String errorMessage) {
        this.success = success;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.documentKey = documentKey;
        this.responseEntry = responseEntry;
        this.errorMessage = errorMessage;
    }
    
    public static ProcessedEntry success(String resourceType, String resourceId, 
                                       String documentKey,
                                       Bundle.BundleEntryComponent responseEntry) {
        return new ProcessedEntry(true, resourceType, resourceId, documentKey, responseEntry, null);
    }
    
    public static ProcessedEntry failed(String errorMessage) {
        return new ProcessedEntry(false, null, null, null, null, errorMessage);
    }
    
    public boolean isSuccess() {
        return success;
    }
    public String getResourceType() {
        return resourceType;
    }
    public String getResourceId() {
        return resourceId;
    }
    public String getDocumentKey() {
        return documentKey;
    }
    public Bundle.BundleEntryComponent getResponseEntry() {
        return responseEntry;
    }
    public String getErrorMessage() {
        return errorMessage;
    }
}