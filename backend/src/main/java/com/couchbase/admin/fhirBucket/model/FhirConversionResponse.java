package com.couchbase.admin.fhirBucket.model;

import java.time.LocalDateTime;

/**
 * Response model for FHIR bucket conversion initiation
 */
public class FhirConversionResponse {
    private String operationId;
    private String bucketName;
    private FhirConversionStatus status;
    private String message;
    private LocalDateTime startedAt;

    public FhirConversionResponse() {}

    public FhirConversionResponse(String operationId, String bucketName, FhirConversionStatus status, String message) {
        this.operationId = operationId;
        this.bucketName = bucketName;
        this.status = status;
        this.message = message;
        this.startedAt = LocalDateTime.now();
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public FhirConversionStatus getStatus() {
        return status;
    }

    public void setStatus(FhirConversionStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }
}
