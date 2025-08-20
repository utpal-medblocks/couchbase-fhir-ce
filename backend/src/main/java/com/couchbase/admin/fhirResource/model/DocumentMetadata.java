package com.couchbase.admin.fhirResource.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DocumentMetadata {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("versionId")
    private String versionId;
    
    @JsonProperty("lastUpdated")
    private String lastUpdated;
    
    @JsonProperty("code")
    private String code;
    
    @JsonProperty("display")
    private String display;
    
    @JsonProperty("deleted")
    private Boolean deleted;
    
    @JsonProperty("isCurrentVersion")
    private Boolean isCurrentVersion;
    
    // Default constructor
    public DocumentMetadata() {
    }
    
    // Constructor with all fields
    public DocumentMetadata(String id, String versionId, String lastUpdated, 
                           String code, String display, Boolean deleted, Boolean isCurrentVersion) {
        this.id = id;
        this.versionId = versionId;
        this.lastUpdated = lastUpdated;
        this.code = code;
        this.display = display;
        this.deleted = deleted;
        this.isCurrentVersion = isCurrentVersion;
    }
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getVersionId() {
        return versionId;
    }
    
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }
    
    public String getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    public String getCode() {
        return code;
    }
    
    public void setCode(String code) {
        this.code = code;
    }
    
    public String getDisplay() {
        return display;
    }
    
    public void setDisplay(String display) {
        this.display = display;
    }
    
    public Boolean getDeleted() {
        return deleted;
    }
    
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
    
    public Boolean getIsCurrentVersion() {
        return isCurrentVersion;
    }
    
    public void setIsCurrentVersion(Boolean isCurrentVersion) {
        this.isCurrentVersion = isCurrentVersion;
    }
    
    @Override
    public String toString() {
        return "DocumentMetadata{" +
                "id='" + id + '\'' +
                ", versionId='" + versionId + '\'' +
                ", lastUpdated='" + lastUpdated + '\'' +
                ", code='" + code + '\'' +
                ", display='" + display + '\'' +
                ", deleted=" + deleted +
                ", isCurrentVersion=" + isCurrentVersion +
                '}';
    }
}
