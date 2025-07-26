package com.couchbase.fhir.resources.search.validation;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.stereotype.Component;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

/**
 * Builder for FHIR-compliant OperationOutcome responses
 * Handles error response creation for FHIR validation and processing errors
 */
@Component
public class FHIROperationOutcomeBuilder {
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private IParser jsonParser;
    
    /**
     * Create OperationOutcome for parameter validation errors
     */
    public OperationOutcome createParameterValidationError(String parameterName, String message) {
        return createError("processing", 
                          String.format("Invalid parameter '%s': %s", parameterName, message),
                          "Parameter validation failed");
    }
    
    /**
     * Create OperationOutcome for multiple conflicting date parameters (matches HAPI error exactly)
     */
    public OperationOutcome createConflictingDateParametersError(String parameterName) {
        return createError("processing",
                          String.format("Can not have multiple date range parameters for the same param without a qualifier"),
                          "Multiple date parameters conflict");
    }
    
    /**
     * Create OperationOutcome for unsupported search parameters
     */
    public OperationOutcome createUnsupportedParameterError(String parameterName, String resourceType) {
        return createError("not-supported",
                          String.format("Search parameter '%s' is not supported for resource type '%s'", parameterName, resourceType),
                          "Unsupported search parameter");
    }
    
    /**
     * Create OperationOutcome for invalid parameter values
     */
    public OperationOutcome createInvalidParameterValueError(String parameterName, String value, String expectedFormat) {
        return createError("invalid",
                          String.format("Invalid value '%s' for parameter '%s'. Expected format: %s", value, parameterName, expectedFormat),
                          "Invalid parameter value");
    }
    
    /**
     * Create OperationOutcome for general search errors
     */
    public OperationOutcome createSearchError(String message) {
        return createError("exception", 
                          message,
                          "Search operation failed");
    }
    
    /**
     * Create generic error OperationOutcome
     */
    private OperationOutcome createError(String issueType, String diagnostics, String details) {
        OperationOutcome outcome = new OperationOutcome();
        
        // Set resource metadata
        outcome.setId(java.util.UUID.randomUUID().toString());
        outcome.getMeta().setLastUpdated(new Date());
        
        // Add issue
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.fromCode(issueType));
        issue.setDiagnostics(diagnostics);
        issue.setDetails(createCodeableConcept("http://terminology.hl7.org/CodeSystem/operation-outcome", 
                                              "MSG_PARAM_INVALID", details));
        
        return outcome;
    }
    
    /**
     * Create warning OperationOutcome
     */
    public OperationOutcome createWarning(String message, String details) {
        OperationOutcome outcome = new OperationOutcome();
        
        outcome.setId(java.util.UUID.randomUUID().toString());
        outcome.getMeta().setLastUpdated(new Date());
        
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
        issue.setCode(OperationOutcome.IssueType.INFORMATIONAL);
        issue.setDiagnostics(message);
        issue.setDetails(createCodeableConcept("http://terminology.hl7.org/CodeSystem/operation-outcome",
                                              "MSG_PARAM_WARNING", details));
        
        return outcome;
    }
    
    /**
     * Convert OperationOutcome to JSON string
     */
    public String toJson(OperationOutcome outcome) {
        return jsonParser.encodeResourceToString(outcome);
    }
    
    /**
     * Helper method to create CodeableConcept
     */
    private org.hl7.fhir.r4.model.CodeableConcept createCodeableConcept(String system, String code, String display) {
        org.hl7.fhir.r4.model.CodeableConcept concept = new org.hl7.fhir.r4.model.CodeableConcept();
        concept.addCoding()
               .setSystem(system)
               .setCode(code)
               .setDisplay(display);
        concept.setText(display);
        return concept;
    }
} 