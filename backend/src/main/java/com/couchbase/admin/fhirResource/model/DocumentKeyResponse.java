package com.couchbase.admin.fhirResource.model;

import java.util.List;

/**
 * Response model for document keys
 */
public class DocumentKeyResponse {
    private String bucketName;
    private String collectionName;
    private List<String> documentKeys;
    private int totalCount;
    private int page;
    private int pageSize;
    private boolean hasMore;

    public DocumentKeyResponse() {}

    public DocumentKeyResponse(String bucketName, String collectionName, List<String> documentKeys, 
                             int totalCount, int page, int pageSize, boolean hasMore) {
        this.bucketName = bucketName;
        this.collectionName = collectionName;
        this.documentKeys = documentKeys;
        this.totalCount = totalCount;
        this.page = page;
        this.pageSize = pageSize;
        this.hasMore = hasMore;
    }

    // Getters and Setters
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

    public List<String> getDocumentKeys() {
        return documentKeys;
    }

    public void setDocumentKeys(List<String> documentKeys) {
        this.documentKeys = documentKeys;
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
}
