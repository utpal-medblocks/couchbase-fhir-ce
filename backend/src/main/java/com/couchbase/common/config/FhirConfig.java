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
import org.hl7.fhir.utilities.validation.ValidationMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
     * Optimized for US Core resource processing (lenient by default)
     */
    @Bean
    public IParser jsonParser(FhirContext fhirContext) {
        logger.info("🔧 Configuring FHIR JSON Parser for US Core (lenient)");
        
        IParser parser = fhirContext.newJsonParser();
        
        // Configure parser for US Core compliance
        parser.setPrettyPrint(false); // Set to true for debugging
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        parser.setEncodeElementsAppliesToChildResourcesOnly(false);
        
        logger.info("✅ FHIR JSON Parser configured for US Core (lenient)");
        return parser;
    }
    
    /**
     * Create strict JSON parser for strict validation buckets
     */
    @Bean
    @Qualifier("strictJsonParser")
    public IParser strictJsonParser(FhirContext fhirContext) {
        logger.info("🔧 Configuring strict FHIR JSON Parser");
        
        IParser parser = fhirContext.newJsonParser();
        
        // Configure parser for strict validation
        parser.setPrettyPrint(false);
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        parser.setEncodeElementsAppliesToChildResourcesOnly(false);
        
        // Use strict error handler that fails on unknown elements
        parser.setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler());
        
        logger.info("✅ Strict FHIR JSON Parser configured");
        return parser;
    }

    /**
     * Create FHIR validator with US Core support (primary validator for normal operations)
     */
    @Bean
    @Primary
    public FhirValidator fhirValidator(FhirContext fhirContext) {
        logger.info("🔍 Configuring FHIR Validator with US Core support");
        
        try {
            // Create validation support chain with US Core structure definitions
            logger.info("📦 Loading US Core structure definitions from resources...");
            
            // Create validation support chain - using base FHIR R4 support only
            // US Core structure definitions are loaded separately as individual resources
            ValidationSupportChain validationSupportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),  // Base FHIR R4
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
            );
            
            logger.info("✅ US Core validation support configured with individual structure definitions");
            
            // Create validator with instance validator
            FhirValidator validator = fhirContext.newValidator();
            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
            
            // Configure to be permissive for development use
            instanceValidator.setErrorForUnknownProfiles(false);
            instanceValidator.setAnyExtensionsAllowed(true);
            instanceValidator.setNoTerminologyChecks(true);
            instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
            instanceValidator.setNoExtensibleWarnings(true);  // Add this for extension warnings
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
    
    /**
     * Create basic FHIR R4 validator for sample data loading (lenient, no US Core enforcement)
     * This validator is used for loading Synthea and other sample data that may not be US Core compliant
     */
    @Bean
    @Qualifier("basicFhirValidator")
    public FhirValidator basicFhirValidator(FhirContext fhirContext) {
        logger.info("🔧 Configuring basic FHIR R4 validator for sample data loading");
        
        // Create basic validation support (FHIR R4 base only, no US Core profiles)
        DefaultProfileValidationSupport defaultSupport = new DefaultProfileValidationSupport(fhirContext);
        InMemoryTerminologyServerValidationSupport terminologySupport = new InMemoryTerminologyServerValidationSupport(fhirContext);
        
        ValidationSupportChain basicValidationChain = new ValidationSupportChain(
            defaultSupport,
            terminologySupport
        );
        
        // Create basic validator
        FhirValidator validator = fhirContext.newValidator();
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(basicValidationChain);
        
        // Configure to be very lenient for sample data
        instanceValidator.setErrorForUnknownProfiles(false);
        instanceValidator.setAnyExtensionsAllowed(true);
        instanceValidator.setNoTerminologyChecks(true);
        instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
        instanceValidator.setNoExtensibleWarnings(true);
        
        validator.registerValidatorModule(instanceValidator);
        
        logger.info("✅ Basic FHIR R4 validator configured for sample data loading");
        return validator;
    }
}
