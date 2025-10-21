package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.couchbase.fhir.resources.exceptions.DatabaseUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps DatabaseUnavailableException to HTTP 503 Service Unavailable.
 * 
 * This tells HAProxy/load balancers to remove this instance from the pool
 * and route traffic to healthy instances.
 */
@Component
@Interceptor
public class DatabaseUnavailableExceptionMapper {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseUnavailableExceptionMapper.class);
    
    @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
    public boolean handleException(RequestDetails theRequestDetails, Throwable theException) {
        
        // Check if this is our database unavailable exception
        if (theException instanceof DatabaseUnavailableException || 
            containsDatabaseUnavailable(theException)) {
            
            // Log once without stack trace (clean logs)
            logger.error("🔴 Database unavailable - returning 503");
            
            // Convert to HAPI's UnclassifiedServerFailureException with 503 status
            throw new ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException(
                503, 
                "Service temporarily unavailable - database connection lost"
            );
        }
        
        // Not our exception, let other handlers deal with it
        return true;
    }
    
    /**
     * Check if the exception chain contains DatabaseUnavailableException.
     */
    private boolean containsDatabaseUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DatabaseUnavailableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

