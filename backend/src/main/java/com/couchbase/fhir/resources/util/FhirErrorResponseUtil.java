package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class FhirErrorResponseUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirErrorResponseUtil.class);
    
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_UNPROCESSABLE = 422;
    
    @Autowired
    private FhirContext fhirContext;
    
    /**
     * Writes a standardized FHIR OperationOutcome error response for bucket not enabled
     */
    public void writeBucketNotEnabledResponse(HttpServletResponse response, String bucketName, String errorMessage) throws IOException {
        OperationOutcome outcome = createBucketNotEnabledOutcome(bucketName, errorMessage);
        writeOperationOutcome(response, outcome, HTTP_BAD_REQUEST);
    }
    
    /**
     * Writes a FHIR OperationOutcome error response for bucket not found
     */
    public void writeBucketNotFoundResponse(HttpServletResponse response, String bucketName) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.NOTFOUND)
            .setDiagnostics(String.format("Bucket '%s' does not exist", bucketName));
            
        writeOperationOutcome(response, outcome, HTTP_NOT_FOUND);
    }
    
    /**
     * Writes a FHIR OperationOutcome error response for validation errors
     */
    public void writeValidationErrorResponse(HttpServletResponse response, String message) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.INVALID)
            .setDiagnostics(message);
            
        writeOperationOutcome(response, outcome, HTTP_UNPROCESSABLE);
    }
    
    /**
     * Writes a generic FHIR OperationOutcome error response
     */
    public void writeErrorResponse(HttpServletResponse response, String message, OperationOutcome.IssueType issueType, int httpStatus) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(issueType)
            .setDiagnostics(message);
            
        writeOperationOutcome(response, outcome, httpStatus);
    }
    
    /**
     * Creates an OperationOutcome for bucket not enabled errors
     */
    private OperationOutcome createBucketNotEnabledOutcome(String bucketName, String errorMessage) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.INVALID)
            .setDiagnostics(String.format(
                "Bucket '%s' is not FHIR-enabled. Please convert it to a FHIR bucket first using the admin interface. Error: %s", 
                bucketName, errorMessage
            ));
        return outcome;
    }
    
    /**
     * Writes an OperationOutcome to the HTTP response
     */
    private void writeOperationOutcome(HttpServletResponse response, OperationOutcome outcome, int httpStatus) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/fhir+json");
        response.setCharacterEncoding("UTF-8");
        
        String outcomeJson = fhirContext.newJsonParser()
            .setPrettyPrint(true)
            .encodeResourceToString(outcome);
        
        response.getWriter().write(outcomeJson);
        response.getWriter().flush();
        
        logger.info("🔍 Returned OperationOutcome with HTTP {}: {}", httpStatus, outcome.getIssueFirstRep().getDiagnostics());
    }
}
