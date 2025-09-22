package com.couchbase.admin.fhirResource.model;

/**
 * Request model for fetching document keys with pagination
 */
public class DocumentKeyRequest {
    private String bucketName;
    private String collectionName;
    private String patientId; // Optional filter for patient-specific documents
    private String resourceType; // Optional filter for General collection resourceType
    private int page = 0;
    private int pageSize = 10;

    public DocumentKeyRequest() {}

    public DocumentKeyRequest(String bucketName, String collectionName) {
        this.bucketName = bucketName;
        this.collectionName = collectionName;
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

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
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

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
}
