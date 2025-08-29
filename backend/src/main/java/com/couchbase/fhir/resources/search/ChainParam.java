package com.couchbase.fhir.resources.search;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a parsed FHIR chained search parameter using HAPI-driven resolution.
 * 
 * Examples:
 * - "patient.name=Smith" → chainField="patient", tailParam="name", value="Smith"
 *   HAPI resolves: patient → Observation.subject targeting Patient → subject.reference
 * - "subject:Patient.name=Smith" → chainField="subject", typeHint="Patient", tailParam="name", value="Smith"
 *   HAPI resolves: subject → Observation.subject targeting Patient → subject.reference
 */
public class ChainParam {
    
    private static final Logger logger = LoggerFactory.getLogger(ChainParam.class);
    
    private final String originalParameter;
    private final String chainField;
    private final String typeHint;
    private final String tailParam;
    private final String value;
    private final String targetResourceType;
    private final String canonicalFieldPath;
    private final String ftsFieldPath;
    
    private ChainParam(String originalParameter, String chainField, String typeHint, String tailParam,
                      String value, String targetResourceType, String canonicalFieldPath, String ftsFieldPath) {
        this.originalParameter = originalParameter;
        this.chainField = chainField;
        this.typeHint = typeHint;
        this.tailParam = tailParam;
        this.value = value;
        this.targetResourceType = targetResourceType;
        this.canonicalFieldPath = canonicalFieldPath;
        this.ftsFieldPath = ftsFieldPath;
    }
    
    /**
     * Parse a chained parameter using HAPI-driven resolution
     * 
     * @param paramKey Parameter key (e.g., "patient.name", "subject:Patient.name")
     * @param paramValue Parameter value (e.g., "Smith", "Hospital")
     * @param sourceResourceType The resource type being searched (e.g., "Observation")
     * @param fhirContext FHIR context for parameter validation
     * @return ChainParam object or null if not a valid chain parameter
     */
    public static ChainParam parse(String paramKey, String paramValue, String sourceResourceType, FhirContext fhirContext) {
        if (paramKey == null || !paramKey.contains(".")) {
            return null; // Not a chained parameter
        }
        
        logger.debug("🔗 Parsing chain parameter: {} = {}", paramKey, paramValue);
        
        try {
            // Step 1: Parse the parameter syntax
            ChainParamParts parts = parseChainSyntax(paramKey);
            if (parts == null) {
                return null;
            }
            
            // Step 2: Resolve chain field via HAPI
            ChainResolution resolution = resolveChainFieldViaHapi(parts.chainField, parts.typeHint, sourceResourceType, fhirContext);
            if (resolution == null) {
                return null;
            }
            
            // Step 3: Validate tail parameter exists on target resource
            if (!validateTailParameter(parts.tailParam, resolution.targetResourceType, fhirContext)) {
                logger.warn("Tail parameter '{}' does not exist on target resource type '{}'", 
                           parts.tailParam, resolution.targetResourceType);
                return null;
            }
            
            logger.info("🔗 Parsed chain: {} → canonical={}, target={}, tailParam={}, ftsField={}", 
                       paramKey, resolution.canonicalFieldPath, resolution.targetResourceType, parts.tailParam, resolution.ftsFieldPath);
            
            return new ChainParam(paramKey, parts.chainField, parts.typeHint, parts.tailParam, paramValue,
                                resolution.targetResourceType, resolution.canonicalFieldPath, resolution.ftsFieldPath);
                                
        } catch (Exception e) {
            logger.warn("Failed to parse chain parameter '{}': {}", paramKey, e.getMessage());
            return null;
        }
    }
    
    // ========== Helper Classes ==========
    
    /**
     * Represents parsed chain parameter syntax
     */
    private static class ChainParamParts {
        final String chainField;
        final String typeHint;
        final String tailParam;
        
        ChainParamParts(String chainField, String typeHint, String tailParam) {
            this.chainField = chainField;
            this.typeHint = typeHint;
            this.tailParam = tailParam;
        }
    }
    
    /**
     * Represents HAPI-resolved chain information
     */
    private static class ChainResolution {
        final String targetResourceType;
        final String canonicalFieldPath;
        final String ftsFieldPath;
        
        ChainResolution(String targetResourceType, String canonicalFieldPath, String ftsFieldPath) {
            this.targetResourceType = targetResourceType;
            this.canonicalFieldPath = canonicalFieldPath;
            this.ftsFieldPath = ftsFieldPath;
        }
    }
    
    // ========== HAPI-Driven Resolution Methods ==========
    
    /**
     * Parse chain parameter syntax to handle both formats:
     * - "patient.name" → chainField="patient", typeHint=null, tailParam="name"
     * - "subject:Patient.name" → chainField="subject", typeHint="Patient", tailParam="name"
     */
    private static ChainParamParts parseChainSyntax(String paramKey) {
        String[] dotParts = paramKey.split("\\.", 2);
        if (dotParts.length != 2) {
            return null;
        }
        
        String chainPart = dotParts[0]; // "patient" or "subject:Patient"
        String tailParam = dotParts[1];  // "name"
        
        // Check for type hint: "subject:Patient"
        if (chainPart.contains(":")) {
            String[] colonParts = chainPart.split(":", 2);
            String chainField = colonParts[0];  // "subject"
            String typeHint = colonParts[1];    // "Patient"
            return new ChainParamParts(chainField, typeHint, tailParam);
        } else {
            // No type hint: "patient"
            return new ChainParamParts(chainPart, null, tailParam);
        }
    }
    
    /**
     * Resolve chain field using HAPI's parameter definitions
     */
    private static ChainResolution resolveChainFieldViaHapi(String chainField, String typeHint, 
                                                           String sourceResourceType, FhirContext fhirContext) {
        try {
            RuntimeSearchParam searchParam = fhirContext
                    .getResourceDefinition(sourceResourceType)
                    .getSearchParam(chainField);
            
            if (searchParam == null) {
                logger.warn("Chain field '{}' does not exist on resource type '{}'", chainField, sourceResourceType);
                return null;
            }
            
            if (!searchParam.getParamType().name().equals("REFERENCE")) {
                logger.warn("Chain field '{}' is not a reference parameter on resource type '{}'", chainField, sourceResourceType);
                return null;
            }
            
            // Get HAPI's canonical path (e.g., "Observation.subject")
            String canonicalPath = searchParam.getPath();
            if (canonicalPath == null) {
                logger.warn("No path found for reference parameter '{}' on resource type '{}'", chainField, sourceResourceType);
                return null;
            }
            
            // Extract FTS field path from canonical path
            String ftsFieldPath = extractFtsFieldPath(canonicalPath);
            
            // Determine target resource type
            String targetResourceType = determineTargetResourceType(searchParam, typeHint, chainField);
            
            return new ChainResolution(targetResourceType, canonicalPath, ftsFieldPath);
            
        } catch (Exception e) {
            logger.warn("Failed to resolve chain field '{}' via HAPI: {}", chainField, e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract FTS field path from HAPI's canonical path
     * e.g., "Observation.subject" → "subject.reference"
     */
    private static String extractFtsFieldPath(String canonicalPath) {
        String[] pathParts = canonicalPath.split("\\.");
        if (pathParts.length >= 2) {
            String fieldName = pathParts[1]; // Get field name after resource type
            return fieldName + ".reference";
        }
        
        // Fallback: use the last part of the path
        return pathParts[pathParts.length - 1] + ".reference";
    }
    
    /**
     * Determine target resource type from HAPI parameter and type hint
     */
    private static String determineTargetResourceType(RuntimeSearchParam searchParam, String typeHint, String chainField) {
        // If explicit type hint provided, use it
        if (typeHint != null && !typeHint.isEmpty()) {
            return typeHint;
        }
        
        // Try to extract from HAPI's target information
        // Note: HAPI R4 doesn't always expose target types directly, so we use heuristics
        
        // Use parameter name semantics as fallback
        return switch (chainField.toLowerCase()) {
            case "patient", "subject" -> "Patient";
            case "practitioner", "performer" -> "Practitioner";
            case "organization" -> "Organization";
            case "encounter" -> "Encounter";
            case "location" -> "Location";
            case "device" -> "Device";
            default -> {
                // Capitalize the field name as last resort
                yield chainField.substring(0, 1).toUpperCase() + chainField.substring(1);
            }
        };
    }
    
    /**
     * Validate that tail parameter exists on target resource type
     */
    private static boolean validateTailParameter(String tailParam, String targetResourceType, FhirContext fhirContext) {
        try {
            RuntimeSearchParam tailSearchParam = fhirContext
                    .getResourceDefinition(targetResourceType)
                    .getSearchParam(tailParam);
            
            return tailSearchParam != null;
        } catch (Exception e) {
            logger.debug("Failed to validate tail parameter '{}' on resource type '{}': {}", 
                        tailParam, targetResourceType, e.getMessage());
            return false;
        }
    }
    
    // Getters
    
    public String getOriginalParameter() {
        return originalParameter;
    }
    
    public String getChainField() {
        return chainField;
    }
    
    public String getTypeHint() {
        return typeHint;
    }
    
    public String getTailParam() {
        return tailParam;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getTargetResourceType() {
        return targetResourceType;
    }
    
    public String getCanonicalFieldPath() {
        return canonicalFieldPath;
    }
    
    public String getFtsFieldPath() {
        return ftsFieldPath;
    }
    
    // Legacy getter for backward compatibility
    public String getReferenceFieldPath() {
        return ftsFieldPath;
    }
    
    // Legacy getter for backward compatibility
    public String getSearchParam() {
        return tailParam;
    }
    
    @Override
    public String toString() {
        return String.format("ChainParam{%s=%s, canonical=%s, target=%s, ftsField=%s}", 
                           originalParameter, value, canonicalFieldPath, targetResourceType, ftsFieldPath);
    }
}
