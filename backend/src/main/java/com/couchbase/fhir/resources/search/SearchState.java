package com.couchbase.fhir.resources.search;

import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.util.Ftsn1qlQueryBuilder.SortField;
import java.util.List;
import java.util.Map;

/**
 * Represents the state of a search operation for pagination purposes.
 * Supports both regular searches and _revinclude operations that require multiple queries
 * and stateful pagination across different resource types.
 */
public class SearchState {
    
    // Search type and common fields
    private String searchType; // "regular" or "revinclude"
    private String originalQuery;
    private String primaryResourceType;
    private int totalPrimaryResources;
    private int currentPrimaryOffset;
    private int pageSize; // The _count parameter from the original request
    private long timestamp;
    private long expiresAt;
    private String bucketName;
    
    // Regular search specific fields
    private Map<String, String> originalSearchCriteria;
    private List<SearchQuery> cachedFtsQueries;
    private List<SortField> sortFields;
    
    // RevInclude search specific fields
    private List<String> primaryResourceIds;
    private String revIncludeResourceType;
    private String revIncludeSearchParam;
    private int totalRevIncludeResources;
    private int currentRevIncludeOffset;
    
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
    public boolean isRegularSearch() {
        return "regular".equals(searchType);
    }
    
    public boolean isRevIncludeSearch() {
        return "revinclude".equals(searchType);
    }
    
    public boolean isPrimaryResourcesExhausted() {
        return currentPrimaryOffset >= totalPrimaryResources;
    }
    
    public boolean hasMoreRevIncludeResources() {
        return currentRevIncludeOffset < totalRevIncludeResources;
    }
    
    public boolean hasMoreRegularResults() {
        return currentPrimaryOffset < totalPrimaryResources;
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
        
        public Builder searchType(String searchType) {
            searchState.searchType = searchType;
            return this;
        }
        
        public Builder originalQuery(String originalQuery) {
            searchState.originalQuery = originalQuery;
            return this;
        }
        
        public Builder primaryResourceType(String primaryResourceType) {
            searchState.primaryResourceType = primaryResourceType;
            return this;
        }
        
        public Builder totalPrimaryResources(int totalPrimaryResources) {
            searchState.totalPrimaryResources = totalPrimaryResources;
            return this;
        }
        
        public Builder currentPrimaryOffset(int currentPrimaryOffset) {
            searchState.currentPrimaryOffset = currentPrimaryOffset;
            return this;
        }
        
        public Builder bucketName(String bucketName) {
            searchState.bucketName = bucketName;
            return this;
        }
        
        public Builder pageSize(int pageSize) {
            searchState.pageSize = pageSize;
            return this;
        }
        
        // Regular search specific builders
        public Builder originalSearchCriteria(Map<String, String> originalSearchCriteria) {
            searchState.originalSearchCriteria = originalSearchCriteria;
            return this;
        }
        
        public Builder cachedFtsQueries(List<SearchQuery> cachedFtsQueries) {
            searchState.cachedFtsQueries = cachedFtsQueries;
            return this;
        }
        
        public Builder sortFields(List<SortField> sortFields) {
            searchState.sortFields = sortFields;
            return this;
        }
        
        // RevInclude search specific builders
        public Builder primaryResourceIds(List<String> primaryResourceIds) {
            searchState.primaryResourceIds = primaryResourceIds;
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
        
        public Builder totalRevIncludeResources(int totalRevIncludeResources) {
            searchState.totalRevIncludeResources = totalRevIncludeResources;
            return this;
        }
        
        public Builder currentRevIncludeOffset(int currentRevIncludeOffset) {
            searchState.currentRevIncludeOffset = currentRevIncludeOffset;
            return this;
        }
        
        public SearchState build() {
            return searchState;
        }
    }
    
    // Getters and setters
    public String getSearchType() {
        return searchType;
    }
    
    public void setSearchType(String searchType) {
        this.searchType = searchType;
    }
    
    public String getOriginalQuery() {
        return originalQuery;
    }
    
    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }
    
    public Map<String, String> getOriginalSearchCriteria() {
        return originalSearchCriteria;
    }
    
    public void setOriginalSearchCriteria(Map<String, String> originalSearchCriteria) {
        this.originalSearchCriteria = originalSearchCriteria;
    }
    
    public List<SearchQuery> getCachedFtsQueries() {
        return cachedFtsQueries;
    }
    
    public void setCachedFtsQueries(List<SearchQuery> cachedFtsQueries) {
        this.cachedFtsQueries = cachedFtsQueries;
    }
    
    public List<SortField> getSortFields() {
        return sortFields;
    }
    
    public void setSortFields(List<SortField> sortFields) {
        this.sortFields = sortFields;
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
    
    public int getPageSize() {
        return pageSize;
    }
    
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
