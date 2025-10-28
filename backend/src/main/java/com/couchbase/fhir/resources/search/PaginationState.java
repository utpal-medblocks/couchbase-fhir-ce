package com.couchbase.fhir.resources.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pagination state for FHIR search operations with query-based pagination.
 * 
 * NEW STRATEGY (Query-Based):
 * 1. Store FTS query + offset (lightweight, ~2KB per state)
 * 2. Each page re-executes FTS query with updated offset
 * 3. _count controls PRIMARY resources only (FHIR compliant)
 * 4. Secondaries (_include/_revinclude) fetched per page, not paginated independently
 * 5. Bundle size capped at maxBundleSize (default 500) to prevent OOM
 * 
 * LEGACY STRATEGY (Document Key List):
 * - Stored up to 1000 document keys (~50KB per state)
 * - Faster but not FHIR compliant for _include/_revinclude
 * - Still supported for backward compatibility
 * 
 * Storage: Couchbase Admin.cache collection (off-heap) to eliminate heap pressure.
 */
public class PaginationState {
    
    @JsonProperty("searchType")
    private final String searchType;           // "regular", "revinclude", "include", "chain"
    
    @JsonProperty("resourceType")
    private final String resourceType;         // Primary resource type being searched
    
    @JsonProperty("allDocumentKeys")
    private final List<String> allDocumentKeys; // All doc keys from initial FTS query (max 1000)
    
    @JsonProperty("pageSize")
    private final int pageSize;               // User-specified page size (default 50)
    
    @JsonProperty("createdAt")
    private final LocalDateTime createdAt;    // For TTL calculation
    
    @JsonProperty("expiresAt")
    private final LocalDateTime expiresAt;    // 3 minutes from creation (configurable)
    
    // Current pagination position
    @JsonProperty("currentOffset")
    private int currentOffset;                // Current position in allDocumentKeys array
    
    // Metadata
    @JsonProperty("bucketName")
    private final String bucketName;
    
    @JsonProperty("baseUrl")
    private final String baseUrl;
    
    // For _revinclude: separate tracking of primary vs secondary resources
    @JsonProperty("primaryResourceCount")
    private final int primaryResourceCount;   // Number of primary resources in allDocumentKeys
    
    // ========== NEW QUERY-BASED PAGINATION FIELDS ==========
    // These fields enable FHIR-compliant pagination where _count applies to PRIMARY resources only
    
    @JsonProperty("primaryFtsQueriesJson")
    private final List<String> primaryFtsQueriesJson;  // Serialized FTS queries for primary resources
    
    @JsonProperty("primaryOffset")
    private int primaryOffset;                         // Current offset in PRIMARY results only
    
    @JsonProperty("primaryPageSize")
    private final int primaryPageSize;                 // _count parameter (primary resources per page)
    
    @JsonProperty("sortFieldsJson")
    private final List<String> sortFieldsJson;         // Serialized sort fields
    
    @JsonProperty("maxBundleSize")
    private final int maxBundleSize;                   // Hard cap on total bundle resources (default 500)
    
    // For _revinclude:
    @JsonProperty("revIncludeResourceType")
    private final String revIncludeResourceType;       // e.g., "Observation"
    
    @JsonProperty("revIncludeSearchParam")
    private final String revIncludeSearchParam;        // e.g., "subject"
    
    // For _include:
    @JsonProperty("includeResourceType")
    private final String includeResourceType;          // e.g., "Practitioner" 
    
    @JsonProperty("includeSearchParam")
    private final String includeSearchParam;           // e.g., "performer"
    
    @JsonProperty("includeParamsList")
    private final List<String> includeParamsList;      // Multiple _include parameters
    
    // Strategy flag
    @JsonProperty("useLegacyKeyList")
    private final boolean useLegacyKeyList;            // true = use allDocumentKeys, false = use query-based
    
    private PaginationState(Builder builder) {
        // Legacy fields
        this.searchType = builder.searchType;
        this.resourceType = builder.resourceType;
        this.allDocumentKeys = builder.allDocumentKeys;
        this.pageSize = builder.pageSize;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plusMinutes(3); // 3 minute TTL (configurable via application.properties)
        this.currentOffset = builder.currentOffset;
        this.bucketName = builder.bucketName;
        this.baseUrl = builder.baseUrl;
        this.primaryResourceCount = builder.primaryResourceCount;
        
        // New query-based fields
        this.primaryFtsQueriesJson = builder.primaryFtsQueriesJson;
        this.primaryOffset = builder.primaryOffset;
        this.primaryPageSize = builder.primaryPageSize;
        this.sortFieldsJson = builder.sortFieldsJson;
        this.maxBundleSize = builder.maxBundleSize;
        this.revIncludeResourceType = builder.revIncludeResourceType;
        this.revIncludeSearchParam = builder.revIncludeSearchParam;
        this.includeResourceType = builder.includeResourceType;
        this.includeSearchParam = builder.includeSearchParam;
        this.includeParamsList = builder.includeParamsList;
        this.useLegacyKeyList = builder.useLegacyKeyList;
    }
    
    // Getters - Legacy fields
    public String getSearchType() { return searchType; }
    public String getResourceType() { return resourceType; }
    public List<String> getAllDocumentKeys() { return allDocumentKeys; }
    public int getPageSize() { return pageSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public int getCurrentOffset() { return currentOffset; }
    public String getBucketName() { return bucketName; }
    public String getBaseUrl() { return baseUrl; }
    public int getPrimaryResourceCount() { return primaryResourceCount; }
    
    // Getters - New query-based fields
    public List<String> getPrimaryFtsQueriesJson() { return primaryFtsQueriesJson; }
    public int getPrimaryOffset() { return primaryOffset; }
    public int getPrimaryPageSize() { return primaryPageSize; }
    public List<String> getSortFieldsJson() { return sortFieldsJson; }
    public int getMaxBundleSize() { return maxBundleSize; }
    public String getRevIncludeResourceType() { return revIncludeResourceType; }
    public String getRevIncludeSearchParam() { return revIncludeSearchParam; }
    public String getIncludeResourceType() { return includeResourceType; }
    public String getIncludeSearchParam() { return includeSearchParam; }
    public List<String> getIncludeParamsList() { return includeParamsList; }
    public boolean isUseLegacyKeyList() { return useLegacyKeyList; }
    
    // State management
    public void setCurrentOffset(int offset) { this.currentOffset = offset; }
    public void setPrimaryOffset(int offset) { this.primaryOffset = offset; }
    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
    
    // Legacy methods (only work with key-list approach)
    public boolean hasMoreResults() { 
        return allDocumentKeys != null && currentOffset < allDocumentKeys.size(); 
    }
    public int getTotalResults() { 
        return allDocumentKeys != null ? allDocumentKeys.size() : 0; 
    }
    public int getRemainingResults() { 
        return allDocumentKeys != null ? Math.max(0, allDocumentKeys.size() - currentOffset) : 0; 
    }
    
    /**
     * Get document keys for the next page (LEGACY - only works with key-list approach)
     */
    public List<String> getNextPageKeys() {
        if (allDocumentKeys == null) {
            return List.of();
        }
        
        int fromIndex = currentOffset;
        int toIndex = Math.min(currentOffset + pageSize, allDocumentKeys.size());
        
        if (fromIndex >= allDocumentKeys.size()) {
            return List.of(); // No more results
        }
        
        return allDocumentKeys.subList(fromIndex, toIndex);
    }
    
    /**
     * Calculate total number of pages (LEGACY - only works with key-list approach)
     */
    public int getTotalPages() {
        if (allDocumentKeys == null) {
            return 0;
        }
        return (int) Math.ceil((double) allDocumentKeys.size() / pageSize);
    }
    
    /**
     * Calculate current page number (1-based) (LEGACY - only works with key-list approach)
     */
    public int getCurrentPage() {
        return (currentOffset / pageSize) + 1;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        // Legacy fields
        private String searchType;
        private String resourceType;
        private List<String> allDocumentKeys;
        private int pageSize;
        private int currentOffset = 0;
        private String bucketName;
        private String baseUrl;
        private int primaryResourceCount = 0;
        
        // New query-based fields
        private List<String> primaryFtsQueriesJson;
        private int primaryOffset = 0;
        private int primaryPageSize = 50;  // Default FHIR page size
        private List<String> sortFieldsJson;
        private int maxBundleSize = 500;  // Default bundle size limit
        private String revIncludeResourceType;
        private String revIncludeSearchParam;
        private String includeResourceType;
        private String includeSearchParam;
        private List<String> includeParamsList;
        private boolean useLegacyKeyList = false;  // Default to new query-based approach
        
        // Legacy field setters
        public Builder searchType(String searchType) { 
            this.searchType = searchType; 
            return this; 
        }
        
        public Builder resourceType(String resourceType) { 
            this.resourceType = resourceType; 
            return this; 
        }
        
        public Builder allDocumentKeys(List<String> allDocumentKeys) { 
            this.allDocumentKeys = allDocumentKeys; 
            return this; 
        }
        
        public Builder pageSize(int pageSize) { 
            this.pageSize = pageSize; 
            return this; 
        }
        
        public Builder currentOffset(int currentOffset) { 
            this.currentOffset = currentOffset; 
            return this; 
        }
        
        public Builder bucketName(String bucketName) { 
            this.bucketName = bucketName; 
            return this; 
        }
        
        public Builder baseUrl(String baseUrl) { 
            this.baseUrl = baseUrl; 
            return this; 
        }
        
        public Builder primaryResourceCount(int primaryResourceCount) { 
            this.primaryResourceCount = primaryResourceCount; 
            return this; 
        }
        
        // New query-based field setters
        public Builder primaryFtsQueriesJson(List<String> primaryFtsQueriesJson) {
            this.primaryFtsQueriesJson = primaryFtsQueriesJson;
            return this;
        }
        
        public Builder primaryOffset(int primaryOffset) {
            this.primaryOffset = primaryOffset;
            return this;
        }
        
        public Builder primaryPageSize(int primaryPageSize) {
            this.primaryPageSize = primaryPageSize;
            return this;
        }
        
        public Builder sortFieldsJson(List<String> sortFieldsJson) {
            this.sortFieldsJson = sortFieldsJson;
            return this;
        }
        
        public Builder maxBundleSize(int maxBundleSize) {
            this.maxBundleSize = maxBundleSize;
            return this;
        }
        
        public Builder revIncludeResourceType(String revIncludeResourceType) {
            this.revIncludeResourceType = revIncludeResourceType;
            return this;
        }
        
        public Builder revIncludeSearchParam(String revIncludeSearchParam) {
            this.revIncludeSearchParam = revIncludeSearchParam;
            return this;
        }
        
        public Builder includeResourceType(String includeResourceType) {
            this.includeResourceType = includeResourceType;
            return this;
        }
        
        public Builder includeSearchParam(String includeSearchParam) {
            this.includeSearchParam = includeSearchParam;
            return this;
        }
        
        public Builder includeParamsList(List<String> includeParamsList) {
            this.includeParamsList = includeParamsList;
            return this;
        }
        
        public Builder useLegacyKeyList(boolean useLegacyKeyList) {
            this.useLegacyKeyList = useLegacyKeyList;
            return this;
        }
        
        public PaginationState build() {
            return new PaginationState(this);
        }
    }
    
    @Override
    public String toString() {
        if (useLegacyKeyList && allDocumentKeys != null) {
            return String.format("PaginationState{type=%s, resource=%s, strategy=LEGACY, keys=%d, pageSize=%d, offset=%d, page=%d/%d}", 
                               searchType, resourceType, allDocumentKeys.size(), pageSize, 
                               currentOffset, getCurrentPage(), getTotalPages());
        } else {
            return String.format("PaginationState{type=%s, resource=%s, strategy=NEW, primaryOffset=%d, primaryPageSize=%d, maxBundle=%d}", 
                               searchType, resourceType, primaryOffset, primaryPageSize, maxBundleSize);
        }
    }
}
