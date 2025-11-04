package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.tenant.UrlBaseTenantIdentificationStrategy;

import com.couchbase.common.config.FhirConfig;

import com.couchbase.fhir.resources.provider.USCoreCapabilityProvider;
import com.couchbase.fhir.resources.interceptor.BucketAwareValidationInterceptor;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
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
    private ResourceProviderAutoConfig resourceProviderAutoConfig;


    
    @Autowired
    private FhirContext fhirContext; // ← Inject your configured context
    
    @Autowired
    private BucketAwareValidationInterceptor bucketValidationInterceptor;
    
    @Autowired
    private FhirBucketConfigService configService;
    
    @Autowired
    private com.couchbase.fhir.resources.interceptor.CleanExceptionInterceptor cleanExceptionInterceptor;
    
    @Autowired
    private com.couchbase.fhir.resources.interceptor.FastpathResponseInterceptor fastpathResponseInterceptor;
    
    @Autowired(required = false)
    private org.springframework.boot.info.BuildProperties buildProperties;

    @Override
    protected void initialize() {
        logger.info("🚀 Initializing FhirRestfulServer");
        
        // Debug: Log what providers we have from autowired list
        logger.info("Found {} providers in autowired list:", providers.size());
        for (IResourceProvider provider : providers) {
            logger.info("  - Provider: {} for resource: {}", 
                       provider.getClass().getSimpleName(), 
                       provider.getResourceType().getSimpleName());
        }
        
        // MANUAL FIX: Get dynamic providers directly from the config bean
        try {
            logger.info("🔧 Manually fetching dynamic providers from ResourceProviderAutoConfig...");
            List<IResourceProvider> dynamicProviders = resourceProviderAutoConfig.dynamicProviders();
            logger.info("📋 Got {} dynamic providers from config", dynamicProviders.size());
            
            // Combine autowired providers with dynamic providers
            List<IResourceProvider> allProviders = new java.util.ArrayList<>(providers);
            allProviders.addAll(dynamicProviders);
            
            logger.info("🎯 Total providers to register: {}", allProviders.size());
            // for (IResourceProvider provider : allProviders) {
            //     logger.info("  ✅ Will register: {} for resource: {}", 
            //                provider.getClass().getSimpleName(), 
            //                provider.getResourceType().getSimpleName());
            // }
            
            setFhirContext(fhirContext); // Use the injected context
            setTenantIdentificationStrategy(new UrlBaseTenantIdentificationStrategy());
            
            
            registerInterceptor(new MultiTenantInterceptor());
            registerInterceptor(bucketValidationInterceptor);
            registerInterceptor(cleanExceptionInterceptor);
            registerInterceptor(fastpathResponseInterceptor); // 🚀 Fastpath JSON bypass (10× memory reduction)
            USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this, buildProperties);
            setServerConformanceProvider(capabilityProvider);
            registerProviders(allProviders); // Register all providers
            
        } catch (Exception e) {
            logger.error("❌ Failed to get dynamic providers, falling back to autowired only: {}", e.getMessage());
            // Fallback to original behavior
            setFhirContext(fhirContext);
            setTenantIdentificationStrategy(new UrlBaseTenantIdentificationStrategy());
            
            
            registerInterceptor(new MultiTenantInterceptor());
            registerInterceptor(bucketValidationInterceptor);
            registerInterceptor(cleanExceptionInterceptor);
            registerInterceptor(fastpathResponseInterceptor); // 🚀 Fastpath JSON bypass (10× memory reduction)
            USCoreCapabilityProvider capabilityProvider = new USCoreCapabilityProvider(this, buildProperties);
            setServerConformanceProvider(capabilityProvider);
            registerProviders(providers);
        }
    }
}
