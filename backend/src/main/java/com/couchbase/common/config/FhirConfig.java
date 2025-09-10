package com.couchbase.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;

// US Core validation support imports
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class FhirConfig {

    private static final Logger logger = LoggerFactory.getLogger(FhirConfig.class);
    
    // US Core search parameter whitelist - populated at startup
    private final Map<String, Set<String>> usCoreSearchParams = new HashMap<>();
    private final Map<String, SearchParameter> usCoreSearchParamDetails = new HashMap<>();

    /**
     * Create FHIR R4 context bean - CLEAN VERSION without search parameter mutation
     */
    @Bean
    public FhirContext fhirContext() {
        logger.info("Initializing FHIR R4 Context for US Core support");
        
        FhirContext fhirContext = FhirContext.forR4();
        
        // Configure for better performance and US Core compatibility
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setDontStripVersionsFromReferencesAtPaths("meta");
        
        logger.info("FHIR R4 Context initialized successfully");
        logger.info("FHIR Version: {}", fhirContext.getVersion().getVersion().getFhirVersionString());
        
        return fhirContext;
    }

    /**
     * Build US Core search parameter whitelist at startup
     * This runs after Spring context initialization
     */
    @PostConstruct
    public void buildUSCoreSearchParamWhitelist() {
        logger.info("Building US Core search parameter whitelist...");
        
        try {
            // Create NPM package support to extract US Core search parameters
            FhirContext tempContext = FhirContext.forR4();
            NpmPackageValidationSupport npmPackageSupport = new NpmPackageValidationSupport(tempContext);
            
            // Load US Core 6.1.0 package
            logger.info("Loading US Core 6.1.0 package for search parameter extraction...");
            npmPackageSupport.loadPackageFromClasspath("classpath:hl7.fhir.us.core-6.1.0.tgz");
            logger.info("US Core package loaded successfully");
            
            // Extract search parameters
            List<IBaseResource> searchParams = npmPackageSupport.fetchAllSearchParameters();
            logger.info("Found {} SearchParameter resources in US Core package", searchParams.size());
            
            int whitelistedCount = 0;
            
            for (IBaseResource resource : searchParams) {
                if (!(resource instanceof SearchParameter)) {
                    continue;
                }
                
                SearchParameter searchParam = (SearchParameter) resource;
                
                // Only process US Core search parameters
                if (searchParam.getUrl() == null || !searchParam.getUrl().contains("us/core")) {
                    continue;
                }
                
                // Skip problematic search parameters
                if (shouldSkipSearchParameter(searchParam)) {
                    continue;
                }
                
                // Add to whitelist per resource type using the CODE field (not name)
                for (org.hl7.fhir.r4.model.CodeType baseType : searchParam.getBase()) {
                    String resourceType = baseType.getCode();
                    String paramCode = searchParam.getCode(); // Use code instead of name
                    
                    usCoreSearchParams.computeIfAbsent(resourceType, k -> new HashSet<>())
                        .add(paramCode);
                    
                    // Store search parameter details for later use (key by code)
                    String key = resourceType + "." + paramCode;
                    usCoreSearchParamDetails.put(key, searchParam);
                    
                    whitelistedCount++;
                    logger.debug("Whitelisted US Core search parameter: {} (code: {}) for {}", 
                               searchParam.getName(), paramCode, resourceType);
                }
            }
            
            logger.info("US Core search parameter whitelist built successfully: {} parameters across {} resource types", 
                       whitelistedCount, usCoreSearchParams.size());
            
            // Log summary of whitelisted parameters by resource type
            for (Map.Entry<String, Set<String>> entry : usCoreSearchParams.entrySet()) {
                logger.debug("  - {}: {} parameters", entry.getKey(), entry.getValue().size());
            }
                       
        } catch (Exception e) {
            logger.warn("Failed to build US Core search parameter whitelist: {}. US Core search parameters will not be available.", e.getMessage());
        }
    }
    
    /**
     * Check if a search parameter is a valid US Core parameter for the given resource type
     */
    public boolean isValidUSCoreSearchParam(String resourceType, String paramName) {
        Set<String> paramsForResource = usCoreSearchParams.get(resourceType);
        return paramsForResource != null && paramsForResource.contains(paramName);
    }
    
    /**
     * Get all US Core search parameters for a resource type
     */
    public Set<String> getUSCoreSearchParams(String resourceType) {
        return usCoreSearchParams.getOrDefault(resourceType, new HashSet<>());
    }
    
    /**
     * Get US Core search parameter details
     */
    public SearchParameter getUSCoreSearchParamDetails(String resourceType, String paramName) {
        String key = resourceType + "." + paramName;
        return usCoreSearchParamDetails.get(key);
    }
    
    /**
     * Get all whitelisted US Core search parameters (for capability statement)
     */
    public Map<String, Set<String>> getAllUSCoreSearchParams() {
        return new HashMap<>(usCoreSearchParams);
    }
    
    /**
     * Determine if a search parameter should be skipped
     */
    private boolean shouldSkipSearchParameter(SearchParameter searchParam) {
        // Skip if essential fields are missing
        if (searchParam.getName() == null || searchParam.getName().trim().isEmpty()) {
            return true;
        }
        
        if (searchParam.getBase() == null || searchParam.getBase().isEmpty()) {
            return true;
        }
        
        if (searchParam.getType() == null) {
            return true;
        }
        
        // Skip if no expression (these cause issues)
        if (searchParam.getExpression() == null || searchParam.getExpression().trim().isEmpty()) {
            return true;
        }
        
        // Skip HAS type if it exists
        if ("HAS".equals(searchParam.getType().toCode())) {
            return true;
        }
        
        return false;
    }

    /**
     * Create JSON parser bean for dependency injection
     */
    @Bean
    public IParser jsonParser(FhirContext fhirContext) {
        logger.info("Configuring FHIR JSON Parser for US Core (lenient)");
        
        IParser parser = fhirContext.newJsonParser();
        parser.setPrettyPrint(false);
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        parser.setEncodeElementsAppliesToChildResourcesOnly(false);
        
        logger.info("FHIR JSON Parser configured for US Core (lenient)");
        return parser;
    }
    
    /**
     * Create strict JSON parser for strict validation buckets
     */
    @Bean
    @Qualifier("strictJsonParser")
    public IParser strictJsonParser(FhirContext fhirContext) {
        logger.info("Configuring strict FHIR JSON Parser");
        
        IParser parser = fhirContext.newJsonParser();
        parser.setPrettyPrint(false);
        parser.setStripVersionsFromReferences(false);
        parser.setOmitResourceId(false);
        parser.setSummaryMode(false);
        parser.setEncodeElementsAppliesToChildResourcesOnly(false);
        parser.setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler());
        
        logger.info("Strict FHIR JSON Parser configured");
        return parser;
    }

    /**
     * Create FHIR validator with US Core support (primary validator for normal operations)
     */
    @Bean
    @Primary
    public FhirValidator fhirValidator(FhirContext fhirContext) {
        logger.info("Configuring FHIR Validator with US Core support");
        
        try {
            // Create NPM package support for US Core
            NpmPackageValidationSupport npmPackageSupport = new NpmPackageValidationSupport(fhirContext);
            
            // Load US Core 6.1.0 package
            logger.info("Loading US Core 6.1.0 package for validation...");
            npmPackageSupport.loadPackageFromClasspath("classpath:hl7.fhir.us.core-6.1.0.tgz");
            logger.info("US Core package loaded successfully for validation");
            
            // Create validation support chain with US Core profiles
            ValidationSupportChain validationSupportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),
                new SnapshotGeneratingValidationSupport(fhirContext),
                npmPackageSupport,
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext)
            );
            
            logger.info("US Core validation support configured successfully");
            
            // Create validator with instance validator
            FhirValidator validator = fhirContext.newValidator();
            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(validationSupportChain);
            
            // Configure for strict US Core validation
            instanceValidator.setErrorForUnknownProfiles(true);
            instanceValidator.setAnyExtensionsAllowed(false);
            instanceValidator.setNoTerminologyChecks(false);
            instanceValidator.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);
            instanceValidator.setNoExtensibleWarnings(false);
            validator.registerValidatorModule(instanceValidator);

            logger.info("FHIR Validator with US Core support configured (strict mode)");
            
            return validator;
            
        } catch (Exception e) {
            logger.error("Failed to configure US Core validator: {}", e.getMessage(), e);
            logger.info("Falling back to basic FHIR validator");
            
            // Fallback to basic validator
            return fhirContext.newValidator();
        }
    }
    
    /**
     * Create basic FHIR R4 validator for sample data loading (lenient, no US Core enforcement)
     */
    @Bean
    @Qualifier("basicFhirValidator")
    public FhirValidator basicFhirValidator(FhirContext fhirContext) {
        logger.info("Configuring basic FHIR R4 validator for sample data loading");
        
        // Create basic validation support
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
        
        logger.info("Basic FHIR R4 validator configured for sample data loading");
        return validator;
    }
}