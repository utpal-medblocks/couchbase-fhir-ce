package com.couchbase.fhir.resources.search.validation;

import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Exception thrown when FHIR search parameter validation fails
 * 
 * This exception is thrown by FhirSearchParameterPreprocessor when:
 * - Parameters don't exist for the resource type
 * - Parameter values have invalid formats
 * - Multiple parameter values conflict (e.g., birthdate=1987-02-20&birthdate=1987-02-21)
 * 
 * Maintains HAPI-centric CE architecture while providing detailed error information
 */
public class FhirSearchValidationException extends RuntimeException {
    
    private final String parameterName;
    private final OperationOutcome operationOutcome;
    
    public FhirSearchValidationException(String message, String parameterName, OperationOutcome operationOutcome) {
        super(message);
        this.parameterName = parameterName;
        this.operationOutcome = operationOutcome;
    }
    
    public FhirSearchValidationException(String message, String parameterName, OperationOutcome operationOutcome, Throwable cause) {
        super(message, cause);
        this.parameterName = parameterName;
        this.operationOutcome = operationOutcome;
    }
    
    public String getParameterName() {
        return parameterName;
    }
    
    public OperationOutcome getOperationOutcome() {
        return operationOutcome;
    }
    
    /**
     * Get a user-friendly error message for API responses
     */
    public String getUserFriendlyMessage() {
        return "Search parameter validation failed: " + getMessage();
    }
}
