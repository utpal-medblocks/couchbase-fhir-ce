package com.couchbase.admin.sampledata.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Progress tracking model for sample data loading
 */
public class SampleDataProgress {
    
    @JsonProperty("totalFiles")
    private int totalFiles;
    
    @JsonProperty("processedFiles")
    private int processedFiles;
    
    @JsonProperty("currentFile")
    private String currentFile;
    
    @JsonProperty("resourcesLoaded")
    private int resourcesLoaded;
    
    @JsonProperty("patientsLoaded")
    private int patientsLoaded;
    
    @JsonProperty("percentComplete")
    private double percentComplete;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("message")
    private String message;
    
    // Constructors
    public SampleDataProgress() {}
    
    public SampleDataProgress(int totalFiles, int processedFiles, String currentFile) {
        this.totalFiles = totalFiles;
        this.processedFiles = processedFiles;
        this.currentFile = currentFile;
        this.percentComplete = totalFiles > 0 ? (double) processedFiles / totalFiles * 100 : 0;
    }
    
    // Getters and Setters
    public int getTotalFiles() {
        return totalFiles;
    }
    
    public void setTotalFiles(int totalFiles) {
        this.totalFiles = totalFiles;
        updatePercentComplete();
    }
    
    public int getProcessedFiles() {
        return processedFiles;
    }
    
    public void setProcessedFiles(int processedFiles) {
        this.processedFiles = processedFiles;
        updatePercentComplete();
    }
    
    public String getCurrentFile() {
        return currentFile;
    }
    
    public void setCurrentFile(String currentFile) {
        this.currentFile = currentFile;
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
    
    public double getPercentComplete() {
        return percentComplete;
    }
    
    public void setPercentComplete(double percentComplete) {
        this.percentComplete = percentComplete;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    private void updatePercentComplete() {
        this.percentComplete = totalFiles > 0 ? (double) processedFiles / totalFiles * 100 : 0;
    }
    
    @Override
    public String toString() {
        return "SampleDataProgress{" +
                "totalFiles=" + totalFiles +
                ", processedFiles=" + processedFiles +
                ", currentFile='" + currentFile + '\'' +
                ", resourcesLoaded=" + resourcesLoaded +
                ", patientsLoaded=" + patientsLoaded +
                ", percentComplete=" + percentComplete +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
} 