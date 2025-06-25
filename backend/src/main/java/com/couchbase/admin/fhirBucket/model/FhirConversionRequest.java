package com.couchbase.admin.fhirBucket.model;

/**
 * Request model for FHIR bucket conversion
 */
public class FhirConversionRequest {
    private String bucketName;

    public FhirConversionRequest() {}

    public FhirConversionRequest(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
}
