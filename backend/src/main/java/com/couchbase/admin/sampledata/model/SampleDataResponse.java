package com.couchbase.admin.sampledata.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response model for sample data operations
 */
public class SampleDataResponse {
    
    @JsonProperty("success")
    private boolean success;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("resourcesLoaded")
    private int resourcesLoaded;
    
    @JsonProperty("patientsLoaded")
    private int patientsLoaded;
    
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("connectionName")
    private String connectionName;
    
    // Constructors
    public SampleDataResponse() {}
    
    public SampleDataResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
    
    public SampleDataResponse(boolean success, String message, int resourcesLoaded, int patientsLoaded) {
        this.success = success;
        this.message = message;
        this.resourcesLoaded = resourcesLoaded;
        this.patientsLoaded = patientsLoaded;
    }
    
    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public int getResourcesLoaded() {
        return resourcesLoaded;
    }
    
    public void setResourcesLoaded(int resourcesLoaded) {
        this.resourcesLoaded = resourcesLoaded;
    }
    
    public int getPatientsLoaded() {
        return patientsLoaded;
    }
    
    public void setPatientsLoaded(int patientsLoaded) {
        this.patientsLoaded = patientsLoaded;
    }
    
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
    
    public String getConnectionName() {
        return connectionName;
    }
    
    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }
    
    @Override
    public String toString() {
        return "SampleDataResponse{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", resourcesLoaded=" + resourcesLoaded +
                ", patientsLoaded=" + patientsLoaded +
                ", bucketName='" + bucketName + '\'' +
                ", connectionName='" + connectionName + '\'' +
                '}';
    }
} 