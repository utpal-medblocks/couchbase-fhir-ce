package com.couchbase.fhir.resources.validation;

/**
 * Exception thrown when a FHIR operation is attempted on a non-FHIR bucket
 */
public class FhirBucketValidationException extends RuntimeException {
    
    public FhirBucketValidationException(String message) {
        super(message);
    }
    
    public FhirBucketValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
