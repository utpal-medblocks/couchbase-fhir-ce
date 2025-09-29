package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Custom exception interceptor that provides clean error logging
 * without overwhelming stack traces for expected FHIR errors.
 */
@Component
@Interceptor
public class CleanExceptionInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(CleanExceptionInterceptor.class);

    @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
    public boolean handleException(RequestDetails theRequestDetails, Throwable theException) {
        // Handle null exception case to prevent NPE
        if (theException == null) {
            logger.warn("🔍 handleException called with null exception - this indicates a bug");
            logger.warn("🔍 Stack trace at point of null exception:", new Exception("Stack trace for debugging"));
            logger.warn("🔍 Request details: {} {}", 
                theRequestDetails.getRequestType(), 
                theRequestDetails.getCompleteUrl());
            return true; // Continue with default handling
        }
        
        if (theRequestDetails instanceof ServletRequestDetails) {
            ServletRequestDetails servletDetails = (ServletRequestDetails) theRequestDetails;
            HttpServletRequest request = servletDetails.getServletRequest();
            
            String method = request.getMethod();
            String url = request.getRequestURL().toString();
            String errorMessage = theException.getMessage();
            
            // Handle ClientAbortException specially - these are client disconnections, not server errors
            if (theException instanceof org.apache.catalina.connector.ClientAbortException ||
                (errorMessage != null && errorMessage.contains("ClientAbortException"))) {
                logger.debug("📡 Client disconnected during response: {} {}", method, url);
                return false; // Suppress completely - this is normal during load testing
            }
            
            // For expected errors, log clean message and prevent further HAPI processing
            if (isExpectedError(errorMessage)) {
                logger.error("🚨 FHIR {} {} failed: {}", method, url, errorMessage);
                return false; // Stop further exception processing to prevent stack trace
            } else {
                // For unexpected errors, log with debug details and let HAPI handle normally
                logger.error("🚨 FHIR {} {} failed: {}", method, url, errorMessage);
                logger.debug("🔍 Full error details:", theException);
                return true; // Continue with HAPI's default exception handling
            }
        }
        return true; // Continue with default handling if not a servlet request
    }
    
    private boolean isExpectedError(String message) {
        return message != null && (
            message.contains("Database transaction error") ||
            message.contains("Bundle processing failed") ||
            message.contains("Bundle type must be") ||
            message.contains("FHIR Validation failed") ||
            message.contains("previously deleted and cannot be reused") ||
            message.contains("is not known") ||
            message.contains("Unknown parameter") ||
            message.contains("Search parameter validation failed") ||
            message.contains("is not FHIR-enabled") ||
            message.contains("Please convert it to a FHIR bucket first") ||
            message.contains("HAPI-0389") ||
            message.contains("HAPI-0418") ||
            message.contains("No Patient found matching the specified criteria") ||
            message.contains("Multiple resources found matching criteria") ||
            message.contains("Multiple Patient resources found matching the specified criteria") ||
            message.contains("Failed to execute search") ||
            // Client-side disconnection errors (common with improved performance)
            message.contains("ClientAbortException") ||
            message.contains("Broken pipe") ||
            message.contains("Connection reset by peer")
          
        );
    }
}
