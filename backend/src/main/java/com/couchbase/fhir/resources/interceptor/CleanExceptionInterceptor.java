package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Custom exception interceptor that provides clean error logging
 * without overwhelming stack traces for expected FHIR errors.
 * Enhanced for container environments with minimal logging.
 */
@Component
@Interceptor
public class CleanExceptionInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(CleanExceptionInterceptor.class);
    
    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
    public boolean handleException(RequestDetails theRequestDetails, Throwable theException) {
        // Handle null exception case to prevent NPE
        // This can happen when HAPI properly catches and converts exceptions to OperationOutcome
        if (theException == null) {
            logger.debug("🔍 handleException called with null (exception already handled by HAPI)");
            return true; // Continue with default handling
        }
        
        // In container environments, suppress ALL exception logging here to prevent
        // HAPI from logging stack traces, since we handle logging in application code
        if (isContainerEnvironment()) {
            String errorMessage = theException.getMessage();
            
            // Completely suppress client disconnection errors
            if (theException instanceof org.apache.catalina.connector.ClientAbortException ||
                (errorMessage != null && (errorMessage.contains("ClientAbortException") || 
                                         errorMessage.contains("Connection reset by peer") ||
                                         errorMessage.contains("Broken pipe")))) {
                return false; // Suppress completely
            }
            
            // For all other exceptions in container mode, suppress HAPI's logging
            // Our application-level loggers will handle the clean logging
            return false;
        }
        
        // Non-container environment - use original logic
        if (theRequestDetails instanceof ServletRequestDetails) {
            ServletRequestDetails servletDetails = (ServletRequestDetails) theRequestDetails;
            HttpServletRequest request = servletDetails.getServletRequest();
            
            String method = request.getMethod();
            String url = request.getRequestURL().toString();
            String errorMessage = theException.getMessage();
            
            // Handle ClientAbortException specially - these are client disconnections, not server errors
            if (theException instanceof org.apache.catalina.connector.ClientAbortException ||
                (errorMessage != null && errorMessage.contains("ClientAbortException"))) {
                if (isDebugEnabled()) {
                    logger.debug("📡 Client disconnected during response: {} {}", method, url);
                }
                return false; // Suppress completely - this is normal during load testing
            }
            
            // For transaction timeouts, use WARN level instead of ERROR in container environments
            if (isTransactionTimeout(errorMessage)) {
                logger.warn("⏱️ FHIR {} {} timeout: {}", method, getSimplifiedUrl(url), getSimplifiedError(errorMessage));
                return false; // Stop further exception processing to prevent stack trace
            }
            
            // For expected errors, log clean message and prevent further HAPI processing
            if (isExpectedError(errorMessage)) {
                logger.warn("⚠️ FHIR {} {} error: {}", method, getSimplifiedUrl(url), getSimplifiedError(errorMessage));
                return false; // Stop further exception processing to prevent stack trace
            } else {
                // For unexpected errors, log with minimal details
                logger.error("🚨 FHIR {} {} unexpected error: {}", method, getSimplifiedUrl(url), getSimplifiedError(errorMessage));
                if (isDebugEnabled()) {
                    logger.debug("🔍 Full error details:", theException);
                }
                return false; // Prevent stack trace
            }
        }
        return false; // Suppress all exception logging in favor of our clean logging
    }
    
    private boolean isContainerEnvironment() {
        return "prod".equals(activeProfile) || 
               System.getenv("DEPLOYED_ENV") != null ||
               System.getProperty("container.mode") != null;
    }
    
    private boolean isDebugEnabled() {
        return !isContainerEnvironment() && logger.isDebugEnabled();
    }
    
    private boolean isTransactionTimeout(String message) {
        return message != null && (
            message.contains("Transaction has expired") ||
            message.contains("configured timeout") ||
            message.contains("Transaction timeout")
        );
    }
    
    private String getSimplifiedUrl(String url) {
        if (url == null) return "unknown";
        // Remove query parameters and shorten for cleaner container logs
        int queryIndex = url.indexOf('?');
        String baseUrl = queryIndex > 0 ? url.substring(0, queryIndex) : url;
        return baseUrl.length() > 100 ? baseUrl.substring(0, 100) + "..." : baseUrl;
    }
    
    private String getSimplifiedError(String message) {
        if (message == null) return "Unknown error";
        // Keep only the essential part of the error message
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
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
            message.contains("HAPI-2544") ||
            message.contains("No Patient found matching the specified criteria") ||
            message.contains("Multiple resources found matching criteria") ||
            message.contains("Multiple Patient resources found matching the specified criteria") ||
            message.contains("Failed to execute search") ||
            // Client-side disconnection errors (common with improved performance)
            message.contains("ClientAbortException") ||
            message.contains("Broken pipe") ||
            message.contains("Version not found") ||
            message.contains("Connection reset by peer") ||
            // Transaction and timeout related errors
            message.contains("Transaction has expired") ||
            message.contains("configured timeout")
        );
    }
}
