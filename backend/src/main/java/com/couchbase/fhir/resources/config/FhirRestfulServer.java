package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.couchbase.fhir.resources.provider.USCoreCapabilityProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom implementation of the HAPI FHIR {@link ca.uhn.fhir.rest.server.RestfulServer}
 * used to configure and bootstrap the FHIR server environment.
 *
 * This class is the central entry point for registering:
 * - Resource providers (e.g., PatientResourceProvider)
 * - System-level providers (e.g., CapabilityStatementProvider)
 * - Interceptors (logging, validation, CORS, etc.)
 * - FHIR context and default response formats
 *
 * It also sets the base path for FHIR endpoints and can be used to control
 * server behavior such as default encoding, error handling, paging, and more.
 *
 */

@RequiredArgsConstructor
@Component
public class FhirRestfulServer extends RestfulServer {

    @Autowired
    private List<IResourceProvider> providers;

    @Override
    protected void initialize() {
        setFhirContext(FhirContext.forR4());
        USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this);
        setServerConformanceProvider(capabilityProvider);
        registerProviders(providers);
    }
}
