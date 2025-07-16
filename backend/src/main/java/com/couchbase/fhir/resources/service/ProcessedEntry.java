package com.couchbase.fhir.resources.service;

import org.hl7.fhir.r4.model.Bundle;
import lombok.Data;
import lombok.AllArgsConstructor;

@Data
@AllArgsConstructor
public class ProcessedEntry {
    private boolean success;
    private String resourceType;
    private String resourceId;
    private String documentKey;
    private Bundle.BundleEntryComponent responseEntry;
    private String errorMessage;
    
    public static ProcessedEntry success(String resourceType, String resourceId, 
                                       String documentKey,
                                       Bundle.BundleEntryComponent responseEntry) {
        return new ProcessedEntry(true, resourceType, resourceId, documentKey, responseEntry, null);
    }
    
    public static ProcessedEntry failed(String errorMessage) {
        return new ProcessedEntry(false, null, null, null, null, errorMessage);
    }
} 