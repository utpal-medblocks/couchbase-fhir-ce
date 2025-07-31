package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import org.springframework.context.ApplicationContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class  FHIRResourceService{

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private FhirContext fhirContext;

    public <T extends IBaseResource> FhirResourceDaoImpl<T> getService(Class<T> resourceClass) {
        return new FhirResourceDaoImpl<>(resourceClass, connectionService, fhirContext);
    }
}
