package com.couchbase.admin.sampledata.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request model for sample data operations
 */
public class SampleDataRequest {
    
    @JsonProperty("connectionName")
    private String connectionName;
    
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("overwriteExisting")
    private boolean overwriteExisting = false;
    
    // Constructors
    public SampleDataRequest() {}
    
    public SampleDataRequest(String connectionName, String bucketName) {
        this.connectionName = connectionName;
        this.bucketName = bucketName;
    }
    
    public SampleDataRequest(String connectionName, String bucketName, boolean overwriteExisting) {
        this.connectionName = connectionName;
        this.bucketName = bucketName;
        this.overwriteExisting = overwriteExisting;
    }
    
    // Getters and Setters
    public String getConnectionName() {
        return connectionName;
    }
    
    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }
    
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
    
    public boolean isOverwriteExisting() {
        return overwriteExisting;
    }
    
    public void setOverwriteExisting(boolean overwriteExisting) {
        this.overwriteExisting = overwriteExisting;
    }
    
    @Override
    public String toString() {
        return "SampleDataRequest{" +
                "connectionName='" + connectionName + '\'' +
                ", bucketName='" + bucketName + '\'' +
                ", overwriteExisting=" + overwriteExisting +
                '}';
    }
} 