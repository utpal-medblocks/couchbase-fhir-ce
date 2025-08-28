package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
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
    public void handleException(RequestDetails theRequestDetails, BaseServerResponseException theException) {
        if (theRequestDetails instanceof ServletRequestDetails) {
            ServletRequestDetails servletDetails = (ServletRequestDetails) theRequestDetails;
            HttpServletRequest request = servletDetails.getServletRequest();
            
            String method = request.getMethod();
            String url = request.getRequestURL().toString();
            String errorMessage = theException.getMessage();
            
            // Log clean error message without stack trace for expected FHIR errors
            logger.error("🚨 FHIR {} {} failed: {}", method, url, errorMessage);
            
            // Only log stack trace for unexpected errors (not our clean Bundle errors)
            if (!isExpectedError(errorMessage)) {
                logger.debug("🔍 Full error details:", theException);
            }
        }
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
            message.contains("HAPI-0389") ||
            message.contains("HAPI-0418") ||
            message.contains("No Patient found matching the specified criteria") ||
            message.contains("Multiple resources found matching criteria") ||
            message.contains("Multiple Patient resources found matching the specified criteria") ||
            message.contains("Failed to execute search")
          
        );
    }
}
