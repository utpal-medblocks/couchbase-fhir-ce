package com.couchbase.fhir.resources.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import com.couchbase.fhir.resources.provider.FhirCouchbaseResourceProvider;
import com.couchbase.fhir.resources.service.FHIRResourceService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ResourceProviderAutoConfig {
    @Autowired
    private FhirContext fhirContext;

    @Autowired
    private FHIRResourceService serviceFactory;

    @Bean
    public List<IResourceProvider> dynamicProviders() {
        List<IResourceProvider> providers = new ArrayList<>();
        FhirContext fhirContext = FhirContext.forR4();
        return fhirContext.getResourceTypes().stream()
                .map(fhirContext::getResourceDefinition)
                .map(rd -> (Class<? extends IBaseResource>) rd.getImplementingClass())
                .distinct()
                .map(clazz -> new FhirCouchbaseResourceProvider<>(clazz, serviceFactory.getService(clazz) , fhirContext))
                .collect(Collectors.toList());

      /*  for (String name : fhirContext.getResourceTypes()) {
            Class<? extends IBaseResource> clazz = fhirContext.getResourceDefinition(name).getImplementingClass();
            providers.add(new FhirCouchbaseResourceProvider<>(clazz, serviceFactory.getService(clazz)));
        }
        return providers;*/
    }

}
