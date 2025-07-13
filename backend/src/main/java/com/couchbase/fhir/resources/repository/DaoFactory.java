package com.couchbase.fhir.resources.repository;

import ca.uhn.fhir.context.FhirContext;
import com.couchbase.admin.connections.service.ConnectionService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DaoFactory {
    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private FhirContext fhirContext;

    public <T extends IBaseResource> FhirResourceDao<T> createDao(Class<T> resourceClass) {
        return new FhirResourceDaoImpl<>(resourceClass, connectionService, fhirContext);
    }
}
