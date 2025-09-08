package com.couchbase.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.validation.FhirValidator;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.ValueSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

// US Core validation support imports
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;

import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.InputStream;
import java.util.Collections;


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
    public FhirValidator fhirValidator(FhirContext fhirContext,
                                       ValidationSupportChain validationSupportChain) {
        logger.info("🔍 Configuring FHIR Validator with US Core support");

        FhirValidator validator = fhirContext.newValidator();
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);

        // Strict US Core validation configuration
        instanceValidator.setErrorForUnknownProfiles(true);
        instanceValidator.setAnyExtensionsAllowed(false);
        instanceValidator.setNoTerminologyChecks(false);
        instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);
        instanceValidator.setNoExtensibleWarnings(false);

        validator.registerValidatorModule(instanceValidator);

        logger.info("✅ FHIR Validator configured with US Core support (strict mode)");
        return validator;
    }


    @Bean
    public ValidationSupportChain validationSupportChain(FhirContext fhirContext) {
        logger.info("🔍 Creating ValidationSupportChain with US Core support");

        PrePopulatedValidationSupport usCoreSupport = new PrePopulatedValidationSupport(fhirContext);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            // Load US Core StructureDefinitions
            Resource[] structureDefs = resolver.getResources("classpath:us_core_6.1.0/StructureDefinition-*.json");
            for (Resource r : structureDefs) {
                try (InputStream is = r.getInputStream()) {
                    StructureDefinition sd = fhirContext.newJsonParser()
                            .parseResource(StructureDefinition.class, is);
                    usCoreSupport.addStructureDefinition(sd);
                } catch (Exception e) {
                    logger.warn("⚠ Failed to load StructureDefinition {}: {}", r.getFilename(), e.getMessage());
                }
            }

            // Load US Core ValueSets
            Resource[] valueSets = resolver.getResources("classpath:us_core_6.1.0/ValueSet-*.json");
            for (Resource r : valueSets) {
                try (InputStream is = r.getInputStream()) {
                    ValueSet vs = fhirContext.newJsonParser()
                            .parseResource(ValueSet.class, is);
                    usCoreSupport.addValueSet(vs);
                } catch (Exception e) {
                    logger.warn("⚠ Failed to load ValueSet {}: {}", r.getFilename(), e.getMessage());
                }
            }

            // Load US Core CodeSystems
            Resource[] codeSystems = resolver.getResources("classpath:us_core_6.1.0/CodeSystem-*.json");
            for (Resource r : codeSystems) {
                try (InputStream is = r.getInputStream()) {
                    CodeSystem cs = fhirContext.newJsonParser()
                            .parseResource(CodeSystem.class, is);
                    usCoreSupport.addCodeSystem(cs);
                } catch (Exception e) {
                    logger.warn("⚠ Failed to load CodeSystem {}: {}", r.getFilename(), e.getMessage());
                }
            }

            // Load US Core SearchParameters
            Resource[] searchParams = resolver.getResources("classpath:us_core_6.1.0/SearchParameter-*.json");
            for (Resource r : searchParams) {
                try (InputStream is = r.getInputStream()) {
                    SearchParameter sp = fhirContext.newJsonParser()
                            .parseResource(SearchParameter.class, is);
                    usCoreSupport.addSearchParameter(sp);
                } catch (Exception e) {
                    logger.warn("⚠ Failed to load SearchParameter {}: {}", r.getFilename(), e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("❌ Failed to load US Core resources: {}", e.getMessage(), e);
        }

        // Create the chain
        ValidationSupportChain chain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),
                usCoreSupport,
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
        );

        logger.info("✅ ValidationSupportChain configured with US Core support");
        return chain;
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
