package com.couchbase.fhir.resources.search;

import java.time.LocalDateTime;
import java.util.List;

/**
 * New pagination state object for the optimized FTS/KV pagination strategy.
 * 
 * Strategy:
 * 1. FTS query fetches up to 1000 document keys upfront (offset=0, size=1000)
 * 2. Store all keys in this state object with TTL
 * 3. Subsequent pages use KV-only operations with stored keys
 * 4. Much faster than repeated FTS queries for each page
 */
public class PaginationState {
    
    private final String searchType;           // "regular", "revinclude", "include", "chain"
    private final String resourceType;         // Primary resource type being searched
    private final List<String> allDocumentKeys; // All doc keys from initial FTS query (max 1000)
    private final int pageSize;               // User-specified page size (default 50)
    private final LocalDateTime createdAt;    // For TTL calculation
    private final LocalDateTime expiresAt;    // 15 minutes from creation
    
    // Current pagination position
    private int currentOffset;                // Current position in allDocumentKeys array
    
    // Metadata
    private final String bucketName;
    private final String baseUrl;
    
    // For _revinclude: separate tracking of primary vs secondary resources
    private final int primaryResourceCount;   // Number of primary resources in allDocumentKeys
    
    private PaginationState(Builder builder) {
        this.searchType = builder.searchType;
        this.resourceType = builder.resourceType;
        this.allDocumentKeys = builder.allDocumentKeys;
        this.pageSize = builder.pageSize;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plusMinutes(15); // 15 minute TTL
        this.currentOffset = builder.currentOffset;
        this.bucketName = builder.bucketName;
        this.baseUrl = builder.baseUrl;
        this.primaryResourceCount = builder.primaryResourceCount;
    }
    
    // Getters
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
    
    // State management
    public void setCurrentOffset(int offset) { this.currentOffset = offset; }
    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
    public boolean hasMoreResults() { return currentOffset < allDocumentKeys.size(); }
    public int getTotalResults() { return allDocumentKeys.size(); }
    public int getRemainingResults() { return Math.max(0, allDocumentKeys.size() - currentOffset); }
    
    /**
     * Get document keys for the next page
     */
    public List<String> getNextPageKeys() {
        int fromIndex = currentOffset;
        int toIndex = Math.min(currentOffset + pageSize, allDocumentKeys.size());
        
        if (fromIndex >= allDocumentKeys.size()) {
            return List.of(); // No more results
        }
        
        return allDocumentKeys.subList(fromIndex, toIndex);
    }
    
    /**
     * Calculate total number of pages
     */
    public int getTotalPages() {
        return (int) Math.ceil((double) allDocumentKeys.size() / pageSize);
    }
    
    /**
     * Calculate current page number (1-based)
     */
    public int getCurrentPage() {
        return (currentOffset / pageSize) + 1;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String searchType;
        private String resourceType;
        private List<String> allDocumentKeys;
        private int pageSize;
        private int currentOffset = 0;
        private String bucketName;
        private String baseUrl;
        private int primaryResourceCount = 0;
        
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
        
        public PaginationState build() {
            return new PaginationState(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format("PaginationState{type=%s, resource=%s, keys=%d, pageSize=%d, offset=%d, page=%d/%d}", 
                           searchType, resourceType, allDocumentKeys.size(), pageSize, 
                           currentOffset, getCurrentPage(), getTotalPages());
    }
}
