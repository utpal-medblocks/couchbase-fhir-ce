package com.couchbase.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.instance.model.api.IBaseResource;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Set;
import java.util.HashSet;

@Configuration
public class FhirConfig {

    private static final Logger logger = LoggerFactory.getLogger(FhirConfig.class);
    
    // Store reference to NPM package support for later use in @PostConstruct
    private NpmPackageValidationSupport npmPackageSupport;
    
    // Store reference to FhirContext for @PostConstruct to use
    private FhirContext fhirContext;

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
        // Store the context for @PostConstruct to use
        this.fhirContext = fhirContext;
        
        logger.info("🔍 Configuring FHIR Validator with US Core support");
        
        try {
            // Create NPM package support for US Core
            this.npmPackageSupport = new NpmPackageValidationSupport(fhirContext);
            
            // Load US Core 6.1.0 package
            logger.info("📦 Loading US Core 6.1.0 package...");
            this.npmPackageSupport.loadPackageFromClasspath("classpath:hl7.fhir.us.core-6.1.0.tgz");
            logger.info("✅ US Core package loaded successfully");
            
            // Create validation support chain with US Core profiles
            ValidationSupportChain validationSupportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),  // Base FHIR R4
                this.npmPackageSupport,  // US Core package
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
            );
            
            logger.info("✅ US Core validation support configured successfully");
            
            // Create validator with instance validator
            FhirValidator validator = fhirContext.newValidator();
            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
            
            // Configure for strict US Core validation
            instanceValidator.setErrorForUnknownProfiles(true);  // Require known profiles
            instanceValidator.setAnyExtensionsAllowed(false);    // Validate extensions
            instanceValidator.setNoTerminologyChecks(false);     // Enable terminology validation
            instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);
            instanceValidator.setNoExtensibleWarnings(false);    // Show extension warnings
            validator.registerValidatorModule(instanceValidator);

            logger.info("✅ FHIR Validator with US Core support configured (strict mode)");
            
            // Register US Core search parameters after the package is loaded
            logger.info("🔍 Extracting and registering US Core search parameters...");
            registerSearchParametersFromPackage(fhirContext, this.npmPackageSupport);
            logger.info("✅ US Core search parameters registered successfully");
            
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
    
    /**
     * Extract and register search parameters from the US Core package
     * This is necessary because NpmPackageValidationSupport only loads for validation
     * but doesn't register search parameters with HAPI's search parameter registry
     */
    private void registerSearchParametersFromPackage(FhirContext fhirContext, NpmPackageValidationSupport npmPackageSupport) {
        try {
            // Get the NPM package support that was created during validator setup
            if (npmPackageSupport == null) {
                logger.warn("Could not find NpmPackageValidationSupport to extract search parameters");
                return;
            }
            
            // Extract and register search parameters from the loaded US Core package
            int registeredCount = 0;
            
            // Get all SearchParameter resources from the package
            List<IBaseResource> searchParams = npmPackageSupport.fetchAllConformanceResources()
                .stream()
                .filter(resource -> resource instanceof SearchParameter)
                .collect(Collectors.toList());
                
            for (IBaseResource resource : searchParams) {
                SearchParameter searchParam = (SearchParameter) resource;
                
                // Only register US Core search parameters
                if (searchParam.getUrl() != null && searchParam.getUrl().contains("us/core")) {
                    registerSearchParameterWithHAPI(fhirContext, searchParam);
                    registeredCount++;
                    logger.debug("Registered US Core search parameter: {}", searchParam.getName());
                }
            }
            
            logger.info("Successfully registered {} US Core search parameters with HAPI", registeredCount);
            
        } catch (Exception e) {
            logger.warn("Failed to register US Core search parameters: {}", e.getMessage());
        }
    }

    private void registerSearchParameterWithHAPI(FhirContext fhirContext, SearchParameter searchParam) {
    try {
        String[] resourceTypes = searchParam.getBase().stream()
            .map(t -> t.getCode())
            .toArray(String[]::new);
            
        for (String resourceType : resourceTypes) {
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            
            if (resourceDef.getSearchParam(searchParam.getName()) == null) {
                // Convert base resource types to Set<String>
                Set<String> baseTypes = searchParam.getBase().stream()
                    .map(t -> t.getCode())
                    .collect(Collectors.toSet());
                
                // Convert targets to Set<String> if they exist
                Set<String> targets = new HashSet<>();
                if (searchParam.hasTarget()) {
                    targets = searchParam.getTarget().stream()
                        .map(t -> t.getCode())
                        .collect(Collectors.toSet());
                }
                
                RuntimeSearchParam runtimeParam = new RuntimeSearchParam(
                    null, // IIdType theId
                    searchParam.getUrl(), // String theUri
                    searchParam.getName(), // String theName
                    searchParam.getDescription(), // String theDescription
                    searchParam.getExpression(), // String thePath
                    convertParamType(searchParam.getType()), // RestSearchParameterTypeEnum theParamType
                    new HashSet<>(), // Set<String> theProvidesMembershipInCompartments
                    targets, // Set<String> theTargets
                    RuntimeSearchParam.RuntimeSearchParamStatusEnum.ACTIVE, // RuntimeSearchParamStatusEnum theStatus
                    baseTypes // Collection<String> theBase
                );
                
                resourceDef.addSearchParam(runtimeParam);
                logger.debug("Successfully registered search parameter {} for resource {}", 
                           searchParam.getName(), resourceType);
            }
        }
    } catch (Exception e) {
        logger.warn("Failed to register search parameter {}: {}", searchParam.getName(), e.getMessage());
    }
}    
    private RestSearchParameterTypeEnum convertParamType(Enumerations.SearchParamType type) {
        return switch (type) {
            case STRING -> RestSearchParameterTypeEnum.STRING;
            case TOKEN -> RestSearchParameterTypeEnum.TOKEN;
            case DATE -> RestSearchParameterTypeEnum.DATE;
            case REFERENCE -> RestSearchParameterTypeEnum.REFERENCE;
            case NUMBER -> RestSearchParameterTypeEnum.NUMBER;
            case URI -> RestSearchParameterTypeEnum.URI;
            case COMPOSITE -> RestSearchParameterTypeEnum.COMPOSITE;
            case QUANTITY -> RestSearchParameterTypeEnum.QUANTITY;
            //case HAS -> RestSearchParameterTypeEnum.HAS;
            case SPECIAL -> RestSearchParameterTypeEnum.SPECIAL;
            default -> RestSearchParameterTypeEnum.STRING;
        };
    }
}
