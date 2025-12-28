package com.couchbase.fhir.auth;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Interceptor to block write operations (POST, PUT, DELETE) on Group resources via FHIR API.
 * Groups can only be managed through the Admin API (/api/admin/groups).
 * 
 * Allowed operations:
 * - GET /fhir/Group/{id} (read)
 * - GET /fhir/Group (search)
 * - GET /fhir/Group/{id}/$export (bulk export)
 * 
 * Blocked operations:
 * - POST /fhir/Group (create) → 405 Method Not Allowed
 * - PUT /fhir/Group/{id} (update) → 405 Method Not Allowed
 * - DELETE /fhir/Group/{id} (delete) → 405 Method Not Allowed
 */
@Component
@Interceptor
public class GroupWriteBlockInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(GroupWriteBlockInterceptor.class);

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
    public void blockGroupWrites(RequestDetails requestDetails) {
        String resourceType = requestDetails.getResourceName();
        
        // Only intercept Group resources
        if (!"Group".equals(resourceType)) {
            return;
        }

        RestOperationTypeEnum operation = requestDetails.getRestOperationType();
        
        // Block write operations
        if (operation == RestOperationTypeEnum.CREATE ||
            operation == RestOperationTypeEnum.UPDATE ||
            operation == RestOperationTypeEnum.DELETE ||
            operation == RestOperationTypeEnum.PATCH) {
            
            logger.warn("🚫 Blocked {} operation on Group resource via FHIR API. " +
                       "Groups can only be managed via Admin API (/api/admin/groups)", 
                       operation);
            
            throw new MethodNotAllowedException(
                    String.format("Group resources cannot be created or modified via the FHIR API. " +
                                 "Please use the Admin API at /api/admin/groups for Group management. " +
                                 "Operation %s is not allowed.", operation));
        }
        
        // Allow read operations (GET, SEARCH, $export, etc.)
        logger.debug("✅ Allowed {} operation on Group resource", operation);
    }
}

