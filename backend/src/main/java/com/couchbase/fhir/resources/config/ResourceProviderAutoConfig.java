package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.validation.FhirValidator;
import com.couchbase.fhir.resources.provider.FhirCouchbaseResourceProvider;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Auto-configuration class that dynamically registers FHIR {@link IResourceProvider} instances
 * for all available FHIR R4 resource types at application startup.
 *
 * <p>This class scans all FHIR R4 resource definitions using the {@link FhirContext} and
 * creates a generic {@link FhirCouchbaseResourceProvider} for each resource. These providers
 * are then registered as Spring beans to support RESTful interactions with all FHIR resources
 * without the need to define them individually.</p>
 *
 * <p>The {@link FHIRResourceService} is used as a factory to provide data access services
 * for each FHIR resource type, allowing for dynamic and scalable backend integration
 * (e.g., Couchbase in this case).</p>
 */

@Component
public class ResourceProviderAutoConfig {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ResourceProviderAutoConfig.class);
    
    // Resources that should NOT be auto-discovered as generic providers
    // Bundle is handled by dedicated BundleTransactionProvider
    private static final Set<Class<? extends Resource>> excludedResources = Set.of(
        Bundle.class  // Handled by BundleTransactionProvider
    );

    public ResourceProviderAutoConfig() {
        logger.info("🚀 ResourceProviderAutoConfig: Constructor called, bean is being instantiated");
    }

    @Autowired
    private FHIRResourceService serviceFactory;
    
    @Autowired
    private FhirSearchParameterPreprocessor searchPreprocessor;
    
    @Autowired
    private FhirBucketValidator bucketValidator;
    
    @Autowired
    private FhirBucketConfigService configService;

    @Autowired
    private FhirContext fhirContext; // Inject singleton FhirContext bean
    
    @Autowired
    private FhirValidator strictValidator; // Primary US Core validator
    
    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator lenientValidator; // Basic validator

    @SuppressWarnings("unchecked")
    @Bean
    public List<IResourceProvider> dynamicProviders() {
    logger.info("🚀 ResourceProviderAutoConfig: Using injected singleton FhirContext");
        return fhirContext.getResourceTypes().stream()
                .map(fhirContext::getResourceDefinition)
                .map(rd -> (Class<? extends Resource>) rd.getImplementingClass())
                .distinct()
                .filter(clazz -> !excludedResources.contains(clazz))
                .map(clazz -> new FhirCouchbaseResourceProvider<>(clazz, serviceFactory.getService(clazz) , fhirContext, searchPreprocessor, bucketValidator, configService, strictValidator, lenientValidator))
                .collect(Collectors.toList());

    }
}
