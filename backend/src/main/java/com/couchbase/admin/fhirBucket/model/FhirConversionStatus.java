package com.couchbase.admin.fhirBucket.model;

/**
 * Enum representing the status of FHIR bucket conversion operation
 */
public enum FhirConversionStatus {
    INITIATED("Conversion initiated"),
    IN_PROGRESS("Conversion in progress"),
    COMPLETED("Conversion completed successfully"),
    FAILED("Conversion failed"),
    CANCELLED("Conversion cancelled");

    private final String description;

    FhirConversionStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
