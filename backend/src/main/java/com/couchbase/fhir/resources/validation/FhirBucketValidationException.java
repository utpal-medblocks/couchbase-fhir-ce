package com.couchbase.fhir.resources.validation;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

/**
 * Exception thrown when a FHIR operation is attempted on a non-FHIR bucket
 * Extends HAPI's InvalidRequestException to return proper 400 Bad Request status
 */
public class FhirBucketValidationException extends InvalidRequestException {
    
    public FhirBucketValidationException(String message) {
        super(message);
    }
    
    public FhirBucketValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
