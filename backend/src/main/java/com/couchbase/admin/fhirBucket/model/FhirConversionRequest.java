package com.couchbase.admin.fhirBucket.model;

/**
 * Request model for FHIR bucket conversion
 */
public class FhirConversionRequest {
    private String bucketName;
    private FhirBucketConfig fhirConfiguration;

    public FhirConversionRequest() {}

    public FhirConversionRequest(String bucketName, FhirBucketConfig fhirConfiguration) {
        this.bucketName = bucketName;
        this.fhirConfiguration = fhirConfiguration;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public FhirBucketConfig getFhirConfiguration() {
        return fhirConfiguration;
    }

    public void setFhirConfiguration(FhirBucketConfig fhirConfiguration) {
        this.fhirConfiguration = fhirConfiguration;
    }
}
