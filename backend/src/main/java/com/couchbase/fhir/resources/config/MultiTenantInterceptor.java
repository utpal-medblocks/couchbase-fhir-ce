package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

public class MultiTenantInterceptor  extends InterceptorAdapter {

    @Override
    public void incomingRequestPreHandled(RestOperationTypeEnum theOperation, RequestDetails theRequestDetails) {
        String tenantId = theRequestDetails.getTenantId();
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }
    }

    @Override
    public void processingCompletedNormally(ServletRequestDetails theRequestDetails) {
        TenantContextHolder.clear();
    }
}
