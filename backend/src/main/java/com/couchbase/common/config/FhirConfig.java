package com.couchbase.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;

// US Core validation support imports
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirConfig {

    private static final Logger logger = LoggerFactory.getLogger(FhirConfig.class);

    /**
     * Create FHIR R4 context bean for dependency injection
     * Configured to support US Core validation
     */
    @Bean
    public FhirContext fhirContext() {
        logger.info("🚀 Initializing FHIR R4 Context for US Core support");
        
        FhirContext fhirContext = FhirContext.forR4();
        
        // Configure for better performance and US Core compatibility
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setDontStripVersionsFromReferencesAtPaths("meta");
        
        logger.info("✅ FHIR R4 Context initialized successfully");
        logger.info("📋 FHIR Version: {}", fhirContext.getVersion().getVersion().getFhirVersionString());
        logger.info("🇺🇸 Ready for US Core validation");
        
        return fhirContext;
    }

    /**
     * Create JSON parser bean for dependency injection
     * Optimized for US Core resource processing
     */
    @Bean
    public IParser jsonParser(FhirContext fhirContext) {
        logger.info("🔧 Configuring FHIR JSON Parser for US Core");
        
        IParser parser = fhirContext.newJsonParser();
        
        // Configure parser for US Core compliance
        parser.setPrettyPrint(false); // Set to true for debugging
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        parser.setEncodeElementsAppliesToChildResourcesOnly(false);
        
        logger.info("✅ FHIR JSON Parser configured for US Core");
        return parser;
    }

    /**
     * Create FHIR validator with US Core support
     */
    @Bean
    public FhirValidator fhirValidator(FhirContext fhirContext) {
        logger.info("🔍 Configuring FHIR Validator with US Core support");
        
        try {
            // Create NPM package support for US Core
            NpmPackageValidationSupport npmPackageSupport = new NpmPackageValidationSupport(fhirContext);
            
            // Load US Core 6.1.0 package
            logger.info("📦 Loading US Core 6.1.0 package...");
            npmPackageSupport.loadPackageFromClasspath("classpath:hl7.fhir.us.core-6.1.0.tgz");
            logger.info("✅ US Core package loaded successfully");
            
            // Create validation support chain
            ValidationSupportChain validationSupportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),  // Base FHIR R4
                npmPackageSupport,  // US Core package
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
            );
            
            // Create validator with instance validator
            FhirValidator validator = fhirContext.newValidator();
            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
            
            // Configure to be permissive for development use
            instanceValidator.setErrorForUnknownProfiles(false);
            instanceValidator.setAnyExtensionsAllowed(true);
            instanceValidator.setNoTerminologyChecks(true);
            instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
            validator.registerValidatorModule(instanceValidator);

            logger.info("✅ FHIR Validator with US Core support configured");
            return validator;
            
        } catch (Exception e) {
            logger.error("❌ Failed to configure US Core validator: {}", e.getMessage(), e);
            logger.info("🔄 Falling back to basic FHIR validator");
            
            // Fallback to basic validator
            FhirValidator validator = fhirContext.newValidator();
            return validator;
        }
    }
}
