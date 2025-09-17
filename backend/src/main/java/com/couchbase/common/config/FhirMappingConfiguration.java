package com.couchbase.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class that explicitly loads FHIR resource mapping from fhir.yml
 * and creates the FhirResourceMappingConfig bean.
 */
@Configuration
public class FhirMappingConfiguration {
    
    private static final String FHIR_CONFIG_PATH = "fhir.yml";
    
    @Bean
    @ConfigurationProperties(prefix = "fhir.mapping")
    public FhirResourceMappingConfig fhirResourceMappingConfig() {
        FhirResourceMappingConfig config = new FhirResourceMappingConfig();
        
        // Load configuration from fhir.yml
        loadMappingFromYaml(config);
        
        return config;
    }
    
    /**
     * Load the mapping configuration from fhir.yml file
     */
    private void loadMappingFromYaml(FhirResourceMappingConfig config) {
        try {
            ClassPathResource resource = new ClassPathResource(FHIR_CONFIG_PATH);
            if (!resource.exists()) {
                throw new RuntimeException("fhir.yml configuration file not found: " + FHIR_CONFIG_PATH);
            }
            
            Yaml yaml = new Yaml();
            try (InputStream inputStream = resource.getInputStream()) {
                Map<String, Object> yamlData = yaml.load(inputStream);
                
                // Load resource to collection mapping
                @SuppressWarnings("unchecked")
                Map<String, Object> mapping = (Map<String, Object>) yamlData.get("mapping");
                if (mapping != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> resourceMapping = (Map<String, String>) mapping.get("resource_to_collection");
                    if (resourceMapping != null) {
                        config.setResourceToCollection(new HashMap<>(resourceMapping));
                    }
                    
                    @SuppressWarnings("unchecked")
                    Map<String, String> collectionMapping = (Map<String, String>) mapping.get("collection_to_fts_index");
                    if (collectionMapping != null) {
                        config.setCollectionToFtsIndex(new HashMap<>(collectionMapping));
                    }
                }                
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load FHIR resource mapping configuration from " + FHIR_CONFIG_PATH, e);
        }
    }
}
