package com.couchbase.fhir.resources.search;

import java.util.List;

/**
 * Represents the state of a search operation for pagination purposes.
 * Used primarily for _revinclude operations that require multiple queries
 * and stateful pagination across different resource types.
 */
public class SearchState {
    
    private String originalQuery;
    private List<String> primaryResourceIds;
    private String primaryResourceType;
    private String revIncludeResourceType;
    private String revIncludeSearchParam;
    private int totalPrimaryResources;
    private int totalRevIncludeResources;
    private int currentPrimaryOffset;
    private int currentRevIncludeOffset;
    private long timestamp;
    private long expiresAt;
    private String bucketName;
    
    // Constructors
    public SearchState() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public SearchState(String originalQuery, List<String> primaryResourceIds, 
                      String primaryResourceType, String revIncludeResourceType,
                      String revIncludeSearchParam, String bucketName) {
        this();
        this.originalQuery = originalQuery;
        this.primaryResourceIds = primaryResourceIds;
        this.primaryResourceType = primaryResourceType;
        this.revIncludeResourceType = revIncludeResourceType;
        this.revIncludeSearchParam = revIncludeSearchParam;
        this.bucketName = bucketName;
    }
    
    // Helper methods
    public boolean isPrimaryResourcesExhausted() {
        return currentPrimaryOffset >= totalPrimaryResources;
    }
    
    public boolean hasMoreRevIncludeResources() {
        return currentRevIncludeOffset < totalRevIncludeResources;
    }
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
    
    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private SearchState searchState = new SearchState();
        
        public Builder originalQuery(String originalQuery) {
            searchState.originalQuery = originalQuery;
            return this;
        }
        
        public Builder primaryResourceIds(List<String> primaryResourceIds) {
            searchState.primaryResourceIds = primaryResourceIds;
            return this;
        }
        
        public Builder primaryResourceType(String primaryResourceType) {
            searchState.primaryResourceType = primaryResourceType;
            return this;
        }
        
        public Builder revIncludeResourceType(String revIncludeResourceType) {
            searchState.revIncludeResourceType = revIncludeResourceType;
            return this;
        }
        
        public Builder revIncludeSearchParam(String revIncludeSearchParam) {
            searchState.revIncludeSearchParam = revIncludeSearchParam;
            return this;
        }
        
        public Builder totalPrimaryResources(int totalPrimaryResources) {
            searchState.totalPrimaryResources = totalPrimaryResources;
            return this;
        }
        
        public Builder totalRevIncludeResources(int totalRevIncludeResources) {
            searchState.totalRevIncludeResources = totalRevIncludeResources;
            return this;
        }
        
        public Builder currentPrimaryOffset(int currentPrimaryOffset) {
            searchState.currentPrimaryOffset = currentPrimaryOffset;
            return this;
        }
        
        public Builder currentRevIncludeOffset(int currentRevIncludeOffset) {
            searchState.currentRevIncludeOffset = currentRevIncludeOffset;
            return this;
        }
        
        public Builder bucketName(String bucketName) {
            searchState.bucketName = bucketName;
            return this;
        }
        
        public SearchState build() {
            return searchState;
        }
    }
    
    // Getters and setters
    public String getOriginalQuery() {
        return originalQuery;
    }
    
    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }
    
    public List<String> getPrimaryResourceIds() {
        return primaryResourceIds;
    }
    
    public void setPrimaryResourceIds(List<String> primaryResourceIds) {
        this.primaryResourceIds = primaryResourceIds;
    }
    
    public String getPrimaryResourceType() {
        return primaryResourceType;
    }
    
    public void setPrimaryResourceType(String primaryResourceType) {
        this.primaryResourceType = primaryResourceType;
    }
    
    public String getRevIncludeResourceType() {
        return revIncludeResourceType;
    }
    
    public void setRevIncludeResourceType(String revIncludeResourceType) {
        this.revIncludeResourceType = revIncludeResourceType;
    }
    
    public String getRevIncludeSearchParam() {
        return revIncludeSearchParam;
    }
    
    public void setRevIncludeSearchParam(String revIncludeSearchParam) {
        this.revIncludeSearchParam = revIncludeSearchParam;
    }
    
    public int getTotalPrimaryResources() {
        return totalPrimaryResources;
    }
    
    public void setTotalPrimaryResources(int totalPrimaryResources) {
        this.totalPrimaryResources = totalPrimaryResources;
    }
    
    public int getTotalRevIncludeResources() {
        return totalRevIncludeResources;
    }
    
    public void setTotalRevIncludeResources(int totalRevIncludeResources) {
        this.totalRevIncludeResources = totalRevIncludeResources;
    }
    
    public int getCurrentPrimaryOffset() {
        return currentPrimaryOffset;
    }
    
    public void setCurrentPrimaryOffset(int currentPrimaryOffset) {
        this.currentPrimaryOffset = currentPrimaryOffset;
    }
    
    public int getCurrentRevIncludeOffset() {
        return currentRevIncludeOffset;
    }
    
    public void setCurrentRevIncludeOffset(int currentRevIncludeOffset) {
        this.currentRevIncludeOffset = currentRevIncludeOffset;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public long getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
}
