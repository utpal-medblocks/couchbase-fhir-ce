package com.couchbase.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirConfig {

    private static final Logger logger = LoggerFactory.getLogger(FhirConfig.class);

    /**
     * Create FHIR R4 context bean for dependency injection
     */
    @Bean
    public FhirContext fhirContext() {
        logger.info("🚀 Initializing FHIR R4 Context");
        
        FhirContext fhirContext = FhirContext.forR4();
        
        // Configure for better performance
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setDontStripVersionsFromReferencesAtPaths("meta");
        
        logger.info("✅ FHIR R4 Context initialized successfully");
        logger.info("📋 FHIR Version: {}", fhirContext.getVersion().getVersion().getFhirVersionString());
        
        return fhirContext;
    }

    /**
     * Create JSON parser bean for dependency injection
     */
    @Bean
    public IParser jsonParser(FhirContext fhirContext) {
        logger.info("🔧 Configuring FHIR JSON Parser");
        
        IParser parser = fhirContext.newJsonParser();
        
        // Configure parser for better output
        parser.setPrettyPrint(false); // Set to true for debugging
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        
        logger.info("✅ FHIR JSON Parser configured");
        return parser;
    }

    /**
     * Create simple FHIR validator bean for dependency injection
     */
    @Bean
    public FhirValidator fhirValidator(FhirContext fhirContext) {
        logger.info("🔍 Configuring basic FHIR Validator");
        
        FhirValidator validator = fhirContext.newValidator();
        
        logger.info("✅ Basic FHIR Validator configured");
        
        return validator;
    }
} 