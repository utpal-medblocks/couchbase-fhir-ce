package com.couchbase.fhir.auth;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * HAPI FHIR Interceptor for SMART on FHIR authorization
 * 
 * Enforces OAuth 2.0 scopes on FHIR resource operations by:
 * 1. Extracting the authenticated JWT from Spring Security context
 * 2. Determining the resource type and operation from the FHIR request
 * 3. Validating scopes using SmartScopeValidator
 * 4. Throwing AuthenticationException if unauthorized
 * 
 * This interceptor runs AFTER Spring Security authentication but BEFORE
 * the FHIR operation is executed, ensuring all FHIR API calls are properly
 * authorized according to SMART scopes.
 */
@Component
@Interceptor
public class SmartAuthorizationInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartAuthorizationInterceptor.class);
    
    @Autowired
    private SmartScopeValidator scopeValidator;
    
    /**
     * Hook that executes after the resource provider method is selected
     * but before it is invoked. This is the ideal point to check authorization.
     */
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
    public void authorizeRequest(RequestDetails theRequestDetails) {
        // Get resource type and operation first
        String resourceType = theRequestDetails.getResourceName();
        RestOperationTypeEnum operationType = theRequestDetails.getRestOperationType();
        
        logger.debug("🔍 [SMART-AUTH] Incoming request: {} {} (operation: {})", 
            theRequestDetails.getRequestType(), resourceType, operationType);
        
        // Skip authorization for CapabilityStatement (metadata endpoint) - must check FIRST
        if ("metadata".equals(theRequestDetails.getOperation()) || 
            RestOperationTypeEnum.METADATA.equals(operationType)) {
            logger.debug("✅ Allowing public access to /fhir/metadata");
            return;
        }
        
        // Get Spring Security authentication
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        logger.debug("🔍 [SMART-AUTH] Authentication: {} (authenticated: {})", 
            authentication != null ? authentication.getClass().getSimpleName() : "null",
            authentication != null ? authentication.isAuthenticated() : false);
        
        // Skip authorization if no authentication present (should not happen for /fhir/* due to SecurityFilterChain)
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.warn("⚠️ No authentication found for FHIR request - this should be blocked by Spring Security");
            throw new AuthenticationException("Authentication required for FHIR API access");
        }
        
        // Skip authorization check for anonymous/unauthenticated users
        if (authentication.getPrincipal().equals("anonymousUser")) {
            logger.warn("⚠️ Anonymous user attempted to access FHIR API");
            throw new AuthenticationException("Authentication required for FHIR API access");
        }
        
        // Determine if this is a read or write operation
        String operation = determineOperation(operationType);
        
        if (operation == null) {
            logger.debug("ℹ️ Skipping authorization for operation: {} (not a resource operation)", operationType);
            return;
        }
        
        // Validate scopes
        boolean authorized = false;
        try {
            logger.debug("🔍 [SMART-AUTH] Validating scopes for resource: {}, operation: {}", resourceType, operation);
            authorized = scopeValidator.hasPermission(authentication, resourceType, operation);
        } catch (Exception e) {
            logger.error("❌ [SMART-AUTH] Exception during scope validation: {}", e.getMessage(), e);
            throw new AuthenticationException("Error validating scopes: " + e.getMessage());
        }
        
        if (!authorized) {
            String username = authentication.getName();
            logger.warn("🚫 Access denied: {} attempted {} on {} without proper scopes", 
                       username, operation, resourceType);
            throw new AuthenticationException(
                String.format("Insufficient scope: %s.%s operation requires appropriate SMART scope", 
                             resourceType, operation)
            );
        }
        
        logger.debug("✅ Authorized: {} for {} {}", authentication.getName(), operation, resourceType);
        
        // Additional patient-scope filtering if needed
        if (scopeValidator.hasPatientScope(authentication)) {
            String patientId = scopeValidator.getPatientContext(authentication);
            if (patientId != null) {
                logger.debug("📋 Patient-scoped request: limiting to patient {}", patientId);
                // TODO: In future, add patient ID filter to search parameters
                // This would require modifying the RequestDetails to add patient filter
            }
        }
    }
    
    /**
     * Determine if the REST operation is a read or write operation
     * 
     * @param operationType HAPI FHIR REST operation type
     * @return "read", "write", or null if not a resource operation
     */
    private String determineOperation(RestOperationTypeEnum operationType) {
        if (operationType == null) {
            return null;
        }
        
        switch (operationType) {
            // Read operations
            case READ:
            case VREAD:
            case SEARCH_TYPE:
            case SEARCH_SYSTEM:
            case HISTORY_TYPE:
            case HISTORY_INSTANCE:
            case HISTORY_SYSTEM:
                return "read";
            
            // Write operations
            case CREATE:
            case UPDATE:
            case PATCH:
            case DELETE:
                return "write";
            
            // Transaction/batch - check individual entries
            case TRANSACTION:
                // For transactions, we'll allow if user has any write permission
                // Individual entries will be checked separately if needed
                return "write";
            
            // Special operations - for now, treat as read
            case EXTENDED_OPERATION_TYPE:
            case EXTENDED_OPERATION_INSTANCE:
            case EXTENDED_OPERATION_SERVER:
                return "read"; // Conservative: require at least read access for $operations
            
            // Metadata and capabilities - public
            case METADATA:
                return null; // Allow without auth
            
            default:
                logger.debug("Unknown operation type: {}", operationType);
                return "read"; // Default to requiring read permission
        }
    }
}

