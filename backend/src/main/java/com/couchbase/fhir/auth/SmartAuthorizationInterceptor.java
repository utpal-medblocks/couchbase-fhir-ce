package com.couchbase.fhir.auth;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import com.couchbase.admin.oauth.service.OAuthClientService;
import com.couchbase.admin.oauth.model.OAuthClient;
import org.hl7.fhir.r4.model.Group;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import ca.uhn.fhir.context.FhirContext;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;

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

    @Autowired
    private OAuthClientService oauthClientService;

    @Autowired
    private FHIRResourceService fhirResourceService;

    @Autowired
    private FhirContext fhirContext;
    
    /**
     * Hook that executes after the resource provider method is selected
     * but before it is invoked. This is the ideal point to check authorization.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
    public void authorizeRequest(RequestDetails theRequestDetails) {
        // Get resource type and operation first
        String resourceType = theRequestDetails.getResourceName();
        RestOperationTypeEnum operationType = theRequestDetails.getRestOperationType();
        
        logger.debug("🔍 [SMART-AUTH] Incoming request: {} {} (operation: {})", 
            theRequestDetails.getRequestType(), resourceType, operationType);
        
            // Skip authorization only for SMART discovery (smart-configuration), both root and /fhir forms
            String reqPath = theRequestDetails.getRequestPath();
            String completeUrl = theRequestDetails.getCompleteUrl();
            boolean isSmartDiscovery =
                    (reqPath != null && (reqPath.contains("/.well-known/smart-configuration")
                                             || reqPath.startsWith("/fhir/.well-known/smart-configuration")))
                 || (completeUrl != null && completeUrl.contains("/.well-known/smart-configuration"));
            if (isSmartDiscovery) {
                logger.debug("✅ Allowing public access to SMART discovery endpoint: path={} url={}", reqPath, completeUrl);
                return;
        }
        
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

        // Enforce bulk-group restrictions for app clients (apz claim starting with "app-")
        try {
            if (authentication instanceof JwtAuthenticationToken) {
                Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
                Object apzClaim = jwt.getClaim("azp");
                if (apzClaim != null) {
                    String apz = apzClaim.toString();
                    if (apz.startsWith("app-")) {
                        Optional<OAuthClient> ocOpt = oauthClientService.getClientById(apz);
                        if (ocOpt.isPresent()) {
                            OAuthClient oc = ocOpt.get();
                            String bulkGroupId = oc.getBulkGroupId();
                            if (bulkGroupId != null && !bulkGroupId.isBlank()) {
                                Optional<Group> bgOpt = oauthClientService.getBulkGroupById(bulkGroupId);
                                if (bgOpt.isPresent()) {
                                    Group group = bgOpt.get();
                                    // Extract patient IDs from FHIR Group members
                                    List<String> patientIds = group.getMember().stream()
                                        .filter(m -> m.hasEntity() && m.getEntity().hasReference())
                                        .map(m -> {
                                            String ref = m.getEntity().getReference();
                                            // Extract ID from "Patient/123" format
                                            if (ref.startsWith("Patient/")) {
                                                return ref.substring("Patient/".length());
                                            }
                                            return ref;
                                        })
                                        .collect(java.util.stream.Collectors.toList());

                                    // Determine requested resource id from the request path
                                    String resource = resourceType;
                                    String resourceId = null;
                                    if (reqPath != null && resource != null) {
                                        String needle = "/" + resource + "/";
                                        int idx = reqPath.indexOf(needle);
                                        if (idx >= 0) {
                                            String after = reqPath.substring(idx + needle.length());
                                            int slash = after.indexOf('/');
                                            resourceId = (slash > 0) ? after.substring(0, slash) : after;
                                        }
                                    }

                                    if (resourceId != null && !resourceId.isBlank()) {
                                        // Patient resource: id must be included in bulk group
                                        if ("Patient".equals(resource)) {
                                            if (!patientIds.contains(resourceId)) {
                                                throw new AccessDeniedException("Forbidden: client app bulk group does not include Patient " + resourceId);
                                            }
                                        } else {
                                            // For other resources: if it has a subject field, ensure it references a Patient in the bulk group
                                            try {
                                                String bucketName = TenantContextHolder.getTenantId();
                                                Class<?> implClass = fhirContext.getResourceDefinition(resource).getImplementingClass();
                                                FhirResourceDaoImpl dao = fhirResourceService.getService((Class) implClass);
                                                Optional<?> resOpt = dao.read(resource, resourceId, bucketName);
                                                if (resOpt.isPresent()) {
                                                    Object resObj = resOpt.get();
                                                    // Try reflection to call getSubject()
                                                    try {
                                                        Method getSubject = resObj.getClass().getMethod("getSubject");
                                                        Object subj = getSubject.invoke(resObj);
                                                        if (subj != null) {
                                                            List<String> referencedPatientIds = new ArrayList<>();
                                                            if (subj instanceof org.hl7.fhir.r4.model.Reference) {
                                                                String ref = ((org.hl7.fhir.r4.model.Reference) subj).getReference();
                                                                if (ref != null && ref.startsWith("Patient/")) {
                                                                    referencedPatientIds.add(ref.substring("Patient/".length()));
                                                                }
                                                            } else if (subj instanceof java.util.List) {
                                                                for (Object item : (java.util.List<?>) subj) {
                                                                    if (item instanceof org.hl7.fhir.r4.model.Reference) {
                                                                        String ref = ((org.hl7.fhir.r4.model.Reference) item).getReference();
                                                                        if (ref != null && ref.startsWith("Patient/")) {
                                                                            referencedPatientIds.add(ref.substring("Patient/".length()));
                                                                        }
                                                                    }
                                                                }

                                                                if (!referencedPatientIds.isEmpty()) {
                                                                    boolean found = referencedPatientIds.stream().anyMatch(patientIds::contains);
                                                                    if (!found) {
                                                                        throw new AccessDeniedException("Forbidden: resource subject does not reference an allowed Patient");
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (NoSuchMethodException nsme) {
                                                        // Resource has no subject accessor - nothing to enforce
                                                    }
                                                }
                                            } catch (Exception e) {
                                                logger.warn("Failed to enforce bulk-group subject check: {}", e.getMessage());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (AccessDeniedException ae) {
            throw ae; // rethrow as-is
        } catch (Exception e) {
            logger.debug("Bulk-group enforcement skipped due to error: {}", e.getMessage());
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

