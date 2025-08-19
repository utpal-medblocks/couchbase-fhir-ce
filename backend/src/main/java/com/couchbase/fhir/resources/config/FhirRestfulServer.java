package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.tenant.UrlBaseTenantIdentificationStrategy;

import com.couchbase.common.config.FhirConfig;
import com.couchbase.fhir.resources.provider.FhirTransactionProvider;
import com.couchbase.fhir.resources.provider.USCoreCapabilityProvider;
import lombok.RequiredArgsConstructor;
import net.sf.saxon.lib.Logger;

import org.slf4j.LoggerFactory;

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
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FhirRestfulServer.class);


    @Autowired
    private List<IResourceProvider> providers;

    @Autowired
    private FhirTransactionProvider transactionProvider;
    
    @Autowired
    private FhirContext fhirContext; // ← Inject your configured context

    @Override
    protected void initialize() {
        logger.info("🚀 Initializing FhirRestfulServer");
        setFhirContext(fhirContext); // Use the injected context
        setTenantIdentificationStrategy(new UrlBaseTenantIdentificationStrategy());
        registerInterceptor(new MultiTenantInterceptor());
        USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this);
        setServerConformanceProvider(capabilityProvider);
        registerProviders(providers);
        // Register system-level transaction provider
        registerProvider(transactionProvider);
    }
}
