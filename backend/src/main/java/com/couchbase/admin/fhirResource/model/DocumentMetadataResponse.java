package com.couchbase.admin.fhirResource.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class DocumentMetadataResponse {
    
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("collectionName")
    private String collectionName;
    
    @JsonProperty("documents")
    private List<DocumentMetadata> documents;
    
    @JsonProperty("totalCount")
    private int totalCount;
    
    @JsonProperty("page")
    private int page;
    
    @JsonProperty("pageSize")
    private int pageSize;
    
    @JsonProperty("hasMore")
    private boolean hasMore;
    
    // Default constructor
    public DocumentMetadataResponse() {
    }
    
    // Constructor with all fields
    public DocumentMetadataResponse(String bucketName, String collectionName, 
                                  List<DocumentMetadata> documents, int totalCount, 
                                  int page, int pageSize, boolean hasMore) {
        this.bucketName = bucketName;
        this.collectionName = collectionName;
        this.documents = documents;
        this.totalCount = totalCount;
        this.page = page;
        this.pageSize = pageSize;
        this.hasMore = hasMore;
    }
    
    // Getters and setters
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
    
    public String getCollectionName() {
        return collectionName;
    }
    
    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }
    
    public List<DocumentMetadata> getDocuments() {
        return documents;
    }
    
    public void setDocuments(List<DocumentMetadata> documents) {
        this.documents = documents;
    }
    
    public int getTotalCount() {
        return totalCount;
    }
    
    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }
    
    public int getPage() {
        return page;
    }
    
    public void setPage(int page) {
        this.page = page;
    }
    
    public int getPageSize() {
        return pageSize;
    }
    
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
    
    public boolean isHasMore() {
        return hasMore;
    }
    
    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
    
    @Override
    public String toString() {
        return "DocumentMetadataResponse{" +
                "bucketName='" + bucketName + '\'' +
                ", collectionName='" + collectionName + '\'' +
                ", documents=" + documents +
                ", totalCount=" + totalCount +
                ", page=" + page +
                ", pageSize=" + pageSize +
                ", hasMore=" + hasMore +
                '}';
    }
}
