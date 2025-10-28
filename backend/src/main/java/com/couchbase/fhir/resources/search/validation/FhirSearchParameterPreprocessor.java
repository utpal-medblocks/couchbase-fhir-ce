package com.couchbase.fhir.resources.search.validation;

import org.springframework.stereotype.Component;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.couchbase.common.config.FhirConfig;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * FHIR Search Parameter Preprocessor and Validator for CE
 * Handles validation BEFORE query execution to catch FHIR compliance issues early
 * 
 * Key responsibilities:
 * - Validate multiple date parameters don't conflict (e.g., birthdate=1987-02-20&birthdate=1987-02-21)
 * - Check parameter existence for resource types
 * - Validate parameter value formats
 * - Detect logically impossible parameter combinations
 * 
 * Maintains HAPI-centric CE architecture while adding sophisticated validation
 */
@Component
public class FhirSearchParameterPreprocessor {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirSearchParameterPreprocessor.class);
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirConfig fhirConfig;
    
    // FHIR prefix pattern for validation
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^(eq|ne|gt|lt|ge|le|sa|eb|ap)(.+)");
    
    /**
     * Main entry point for parameter preprocessing and validation
     * @param resourceType FHIR resource type (e.g., "Patient")
     * @param allParams All query parameters with multiple values per key
     * @throws FhirSearchValidationException if validation fails
     */
    public void validateSearchParameters(String resourceType, Map<String, List<String>> allParams) {
        logger.debug("🔍 Validating search parameters for {} - {} parameters", resourceType, allParams.size());
        
        try {
            // Step 1: Validate parameter existence and basic format
            validateParameterExistence(resourceType, allParams);
            
            // Step 2: Validate parameter value formats
            validateParameterFormats(resourceType, allParams);
            
            // Step 3: Validate parameter consistency (main use case)
            validateParameterConsistency(resourceType, allParams);
            
            logger.debug("✅ Parameter validation passed for {}", resourceType);
            
        } catch (FhirSearchValidationException e) {
            logger.warn("❌ Parameter validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("❌ Unexpected validation error: {}", e.getMessage());
            throw new FhirSearchValidationException("Validation failed: " + e.getMessage(), "", null);
        }
    }
    
    /**
     * Validate that parameters exist for the resource type
     * Now checks US Core whitelist in addition to HAPI's built-in parameters
     */
    private void validateParameterExistence(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (String paramName : allParams.keySet()) {
            // Skip control parameters and framework parameters
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            // Check if this is a chained parameter (contains dot)
            if (paramName.contains(".")) {
                // This is a chained parameter like "patient.name" - validate the chain field
                String chainField = paramName.substring(0, paramName.indexOf("."));
                
                // Check both HAPI registry and US Core whitelist for chain field
                RuntimeSearchParam chainParam = resourceDef.getSearchParam(chainField);
                boolean isUSCoreParam = fhirConfig.isValidUSCoreSearchParam(resourceType, chainField);
                
                if (chainParam == null && !isUSCoreParam) {
                    logger.warn("⚠️ Unknown chain field '{}' for resource type {}", chainField, resourceType);
                    throw new FhirSearchValidationException(
                        "Unknown chain field: " + chainField + " for resource type " + resourceType, 
                        chainField, 
                        null
                    );
                }
                
                // If we have HAPI param, validate it's a reference type
                if (chainParam != null && !chainParam.getParamType().name().equals("REFERENCE")) {
                    logger.warn("⚠️ Chain field '{}' is not a reference parameter for resource type {}", chainField, resourceType);
                    throw new FhirSearchValidationException(
                        "Chain field " + chainField + " is not a reference parameter for resource type " + resourceType, 
                        chainField, 
                        null
                    );
                }
                
                // If it's a US Core param, check if it's a reference type
                if (isUSCoreParam && chainParam == null) {
                    org.hl7.fhir.r4.model.SearchParameter usCoreParam = fhirConfig.getUSCoreSearchParamDetails(resourceType, chainField);
                    if (usCoreParam != null && usCoreParam.getType() != org.hl7.fhir.r4.model.Enumerations.SearchParamType.REFERENCE) {
                        logger.warn("⚠️ US Core chain field '{}' is not a reference parameter for resource type {}", chainField, resourceType);
                        throw new FhirSearchValidationException(
                            "Chain field " + chainField + " is not a reference parameter for resource type " + resourceType, 
                            chainField, 
                            null
                        );
                    }
                }
                
                logger.debug("✅ Validated chain parameter: {} ({})", paramName, 
                           chainParam != null ? "HAPI" : "US Core");
                continue; // Skip normal validation for chain parameters
            }
            
            // Extract base parameter name (remove modifiers like :exact)
            String baseParamName = paramName.contains(":") ? paramName.substring(0, paramName.indexOf(":")) : paramName;
            
            // Check both HAPI registry and US Core whitelist
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(baseParamName);
            boolean isUSCoreParam = fhirConfig.isValidUSCoreSearchParam(resourceType, baseParamName);
            
            if (searchParam == null && !isUSCoreParam) {
                // Debug logging: show what parameters ARE available
                Set<String> availableUSCoreParams = fhirConfig.getUSCoreSearchParams(resourceType);
                logger.warn("⚠️ Unknown search parameter '{}' for resource type {} (not in HAPI registry or US Core whitelist)", 
                           baseParamName, resourceType);
                logger.debug("🔍 Available US Core parameters for {}: {}", resourceType, availableUSCoreParams);
                
                // Also log HAPI parameters for comparison
                Set<String> hapiParams = resourceDef.getSearchParams().stream()
                    .map(param -> param.getName())
                    .collect(Collectors.toSet());
                logger.debug("🔍 Available HAPI parameters for {}: {}", resourceType, hapiParams);
                
                throw new FhirSearchValidationException(
                    "Unknown parameter: " + baseParamName + " for resource type " + resourceType + 
                    ". Parameter must be either a standard FHIR parameter or a US Core parameter.", 
                    baseParamName, 
                    null
                );
            }
            
            // Log which source validated the parameter
            if (searchParam != null && isUSCoreParam) {
                logger.debug("✅ Parameter '{}' found in both HAPI registry and US Core whitelist", baseParamName);
            } else if (searchParam != null) {
                logger.debug("✅ Parameter '{}' found in HAPI registry", baseParamName);
            } else {
                logger.debug("✅ Parameter '{}' found in US Core whitelist", baseParamName);
            }
        }
    }
    
    /**
     * Validate parameter value formats
     * Now handles both HAPI registry and US Core parameters
     */
    private void validateParameterFormats(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
            String paramName = entry.getKey();
            List<String> values = entry.getValue();
            
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            // Skip chain parameters - they have their own validation logic
            if (paramName.contains(".")) {
                continue;
            }
            
            // Extract base parameter name (remove modifiers)
            String baseParamName = paramName.contains(":") ? paramName.substring(0, paramName.indexOf(":")) : paramName;
            
            // Try HAPI registry first
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(baseParamName);
            if (searchParam != null) {
                for (String value : values) {
                    validateParameterFormat(searchParam, value, paramName);
                }
            } else {
                // Check if it's a US Core parameter and validate using US Core details
                org.hl7.fhir.r4.model.SearchParameter usCoreParam = fhirConfig.getUSCoreSearchParamDetails(resourceType, baseParamName);
                if (usCoreParam != null) {
                    for (String value : values) {
                        validateUSCoreParameterFormat(usCoreParam, value, paramName);
                    }
                }
            }
        }
    }
    
    /**
     * Validate parameter consistency - MAIN USE CASE
     * This catches conflicts like birthdate=1987-02-20&birthdate=1987-02-21
     */
    private void validateParameterConsistency(String resourceType, Map<String, List<String>> allParams) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        
        for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
            String paramName = entry.getKey();
            List<String> values = entry.getValue();
            
            if (paramName.startsWith("_") || isFrameworkParameter(paramName)) {
                continue;
            }
            
            // Extract base parameter name (remove modifiers)
            String baseParamName = paramName.contains(":") ? paramName.substring(0, paramName.indexOf(":")) : paramName;
            
            RuntimeSearchParam searchParam = resourceDef.getSearchParam(baseParamName);
            if (searchParam != null && values.size() > 1) {
                validateMultipleParameterValues(searchParam, values, paramName);
            }
        }
    }
    
    /**
     * Validate US Core parameter format
     */
    private void validateUSCoreParameterFormat(org.hl7.fhir.r4.model.SearchParameter usCoreParam, String value, String paramName) {
        org.hl7.fhir.r4.model.Enumerations.SearchParamType paramType = usCoreParam.getType();
        
        try {
            switch (paramType) {
                case DATE:
                    validateDateFormat(value, paramName);
                    break;
                case NUMBER:
                    validateNumberFormat(value, paramName);
                    break;
                case TOKEN:
                    validateTokenFormat(value, paramName);
                    break;
                case REFERENCE:
                    validateReferenceFormat(value, paramName);
                    break;
                case STRING:
                    validateStringFormat(value, paramName);
                    break;
                default:
                    // Other types (URI, QUANTITY, COMPOSITE, SPECIAL) are more flexible
                    break;
            }
        } catch (Exception e) {
            throw new FhirSearchValidationException(
                "Invalid format for US Core parameter " + paramName + ": " + e.getMessage(), 
                paramName, 
                null
            );
        }
    }

    /**
     * Validate individual parameter format
     */
    private void validateParameterFormat(RuntimeSearchParam searchParam, String value, String paramName) {
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        try {
            switch (paramType) {
                case DATE:
                    validateDateFormat(value, paramName);
                    break;
                case NUMBER:
                    validateNumberFormat(value, paramName);
                    break;
                case TOKEN:
                    validateTokenFormat(value, paramName);
                    break;
                case REFERENCE:
                    validateReferenceFormat(value, paramName);
                    break;
                case STRING:
                    validateStringFormat(value, paramName);
                    break;
                default:
                    // Other types (URI, QUANTITY, COMPOSITE, SPECIAL, HAS) are more flexible
                    break;
            }
        } catch (Exception e) {
            throw new FhirSearchValidationException(
                "Invalid format for parameter " + paramName + ": " + e.getMessage(), 
                paramName, 
                null
            );
        }
    }
    
    /**
     * Validate multiple values for the same parameter - CORE LOGIC
     */
    private void validateMultipleParameterValues(RuntimeSearchParam searchParam, List<String> values, String paramName) {
        RestSearchParameterTypeEnum paramType = searchParam.getParamType();
        
        switch (paramType) {
            case DATE:
                validateMultipleDateValues(values, paramName);
                break;
            case NUMBER:
                validateMultipleNumberValues(values, paramName);
                break;
            case TOKEN:
                // TOKEN parameters with multiple values are usually OR logic (valid)
                // e.g., gender=male,female would find male OR female patients
                validateMultipleTokenValues(values, paramName);
                break;
            case REFERENCE:
                // REFERENCE parameters with multiple values are usually OR logic (valid)
                break;
            case STRING:
                // STRING parameters with multiple values are usually OR logic (valid)
                break;
            default:
                // Other types (URI, QUANTITY, COMPOSITE, SPECIAL, HAS) typically allow multiple values as OR logic
                break;
        }
    }
    
    /**
     * Validate multiple date values - SPECIFIC CASE
     * The rule: Multiple date parameters without explicit prefixes are invalid
     * because a birthdate can't be two different values simultaneously
     */
    private void validateMultipleDateValues(List<String> values, String paramName) {
        logger.debug("Validating multiple date values for {}: {}", paramName, values);
        
        List<String> implicitEqualityValues = new ArrayList<>();
        List<String> explicitPrefixValues = new ArrayList<>();
        
        // Separate implicit equality (no prefix) from explicit prefix values
        for (String value : values) {
            if (hasExplicitPrefix(value)) {
                explicitPrefixValues.add(value);
            } else {
                implicitEqualityValues.add(value);
            }
        }
        
        // Rule 1: Multiple implicit equality values are INVALID
        // e.g., birthdate=1987-02-20&birthdate=1987-02-21
        if (implicitEqualityValues.size() > 1) {
            logger.warn("❌ Multiple implicit equality date values detected: {}", implicitEqualityValues);
            throw new FhirSearchValidationException(
                "Cannot have multiple date values without prefixes for parameter " + paramName + 
                ". Use prefixes like ge, le to specify ranges.", 
                paramName, 
                null
            );
        }
        
        // Rule 2: Mixed implicit and explicit values are INVALID
        // e.g., birthdate=1987-02-20&birthdate=ge1987-01-01
        if (!implicitEqualityValues.isEmpty() && !explicitPrefixValues.isEmpty()) {
            logger.warn("❌ Mixed implicit and explicit date values detected");
            throw new FhirSearchValidationException(
                "Cannot mix date values with and without prefixes for parameter " + paramName, 
                paramName, 
                null
            );
        }
        
        // Rule 3: Multiple explicit prefix values are usually valid (ranges)
        // e.g., birthdate=ge1987-01-01&birthdate=le1987-12-31 (born in 1987)
        logger.debug("✅ Date validation passed for {}", paramName);
    }
    
    /**
     * Validate multiple number values
     */
    private void validateMultipleNumberValues(List<String> values, String paramName) {
        // Similar logic to dates - multiple exact values are usually invalid
        List<String> implicitEqualityValues = new ArrayList<>();
        
        for (String value : values) {
            if (!hasExplicitPrefix(value)) {
                implicitEqualityValues.add(value);
            }
        }
        
        if (implicitEqualityValues.size() > 1) {
            throw new FhirSearchValidationException(
                "Cannot have multiple number values without prefixes for parameter " + paramName, 
                paramName, 
                null
            );
        }
    }
    
    /**
     * Validate multiple token values
     */
    private void validateMultipleTokenValues(List<String> values, String paramName) {
        // Some token fields should only have single values
        if (isSingleValueTokenField(paramName) && values.size() > 1) {
            throw new FhirSearchValidationException(
                "Parameter " + paramName + " should only have a single value", 
                paramName, 
                null
            );
        }
    }
    
    // ========== Format Validation Methods ==========
    
    private void validateDateFormat(String value, String paramName) {
        try {
            String dateValue = value.replaceFirst("^(eq|ne|gt|lt|ge|le|sa|eb|ap)", "");
            
            if (dateValue.length() == 10) {
                // Date only format: YYYY-MM-DD
                LocalDate.parse(dateValue, DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                // DateTime format: YYYY-MM-DDTHH:MM:SS[.mmm][Z|±HH:MM]
                Instant.parse(dateValue);
            }
        } catch (Exception e) {
            throw new RuntimeException("Invalid date format: " + value + ". Expected YYYY-MM-DD or ISO datetime format.");
        }
    }
    
    private void validateNumberFormat(String value, String paramName) {
        try {
            String numberValue = value.replaceFirst("^(eq|ne|gt|lt|ge|le|ap)", "");
            Double.parseDouble(numberValue);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number format: " + value);
        }
    }
    
    private void validateTokenFormat(String value, String paramName) {
        // Token can be: code, system|code, |code, system|
        if (value.contains("|")) {
            String[] parts = value.split("\\|", 2);
            // Basic validation - ensure no invalid characters
            if (parts[0].trim().isEmpty() && parts[1].trim().isEmpty()) {
                throw new RuntimeException("Invalid token format: " + value + ". Expected code or system|code format.");
            }
        }
    }
    
    private void validateReferenceFormat(String value, String paramName) {
        // Reference can be: ResourceType/id, relative/absolute URL, or just id
        if (value.contains("/")) {
            String[] parts = value.split("/", 2);
            if (parts.length != 2 || parts[1].trim().isEmpty()) {
                throw new RuntimeException("Invalid reference format: " + value + ". Expected ResourceType/id format.");
            }
        }
    }
    
    private void validateStringFormat(String value, String paramName) {
        if (value.trim().isEmpty()) {
            throw new RuntimeException("Empty string value for parameter " + paramName);
        }
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Check if a value has an explicit FHIR prefix
     */
    private boolean hasExplicitPrefix(String value) {
        return PREFIX_PATTERN.matcher(value).matches();
    }
    
    /**
     * Check if parameter is a framework parameter (like connectionName, bucketName)
     */
    private boolean isFrameworkParameter(String paramName) {
        return paramName.equals("connectionName") || paramName.equals("bucketName");
    }
    
    /**
     * Check if a token field should only have single values
     */
    private boolean isSingleValueTokenField(String paramName) {
        Set<String> singleValueFields = Set.of("gender", "active", "deceased");
        return singleValueFields.contains(paramName);
    }
}
