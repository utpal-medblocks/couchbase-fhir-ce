package com.couchbase.fhir.resources.search.validation;

import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Custom exception class that carries OperationOutcome information for FHIR search validation errors
 */
public class FHIRSearchValidationException extends RuntimeException {
    private final OperationOutcome operationOutcome;
    private final String parameterName;

    public FHIRSearchValidationException(String message, String parameterName, OperationOutcome operationOutcome) {
        super(message);
        this.parameterName = parameterName;
        this.operationOutcome = operationOutcome;
    }

    public OperationOutcome getOperationOutcome() {
        return operationOutcome;
    }

    public String getParameterName() {
        return parameterName;
    }
} 