package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
public class FhirCreateService {

    private static final Logger logger = LoggerFactory.getLogger(FhirCreateService.class);

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirContext fhirContext;  // ✅ Inject the configured context
    
    @Autowired
    private FhirValidator fhirValidator;  // ✅ Primary US Core validator
    
    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator basicFhirValidator;  // ✅ Basic validator for lenient validation
    
    @Autowired
    private IParser jsonParser;       // ✅ Inject the configured parser
    
    @Autowired
    private FhirAuditService auditService;  // ✅ Inject the audit service
    
    @Autowired
    private FhirBundleProcessingService bundleProcessor;  // ✅ Inject bundle processor
    
    @Autowired
    private FhirResourceStorageHelper storageHelper;  // ✅ Inject storage helper

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";
    
    // Reuse ObjectMapper for performance - expensive to create each time
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public FhirCreateService() {
        // Empty - Spring will inject dependencies
    }
    
    @PostConstruct
    private void init() {
        logger.info("🚀 FhirCreateService initialized with FHIR R4 context");
        
        // Configure parser for optimal performance
        jsonParser.setPrettyPrint(false);                    // ✅ No formatting overhead
        jsonParser.setStripVersionsFromReferences(false);    // Skip processing
        jsonParser.setOmitResourceId(false);                 // Keep IDs as-is
        jsonParser.setSummaryMode(false);                    // Full resources
        jsonParser.setOverrideResourceIdWithBundleEntryFullUrl(false); // Big performance gain
        
        // Context-level optimizations
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false);
        
        logger.info("✅ FHIR Create Service optimized for high-performance resource creation");
    }

    /**
     * Create resource using the storage helper with strict US Core validation (default)
     */
    public Map<String, Object> createResource(String resourceType, String connectionName, 
                                            String bucketName, Map<String, Object> resourceData) {
        return createResource(resourceType, connectionName, bucketName, resourceData, false);
    }
    
    /**
     * Create resource using the storage helper with configurable FHIR validation
     * @param resourceType FHIR resource type
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name
     * @param resourceData Resource data as Map
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     */
    public Map<String, Object> createResource(String resourceType, String connectionName, 
                                            String bucketName, Map<String, Object> resourceData, 
                                            boolean useLenientValidation) {
        try {
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            logger.info("🚀 Creating FHIR {} resource with {} validation", resourceType, validationType);
            
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Step 1: Validate and prepare the FHIR resource with specified validation mode
            Map<String, Object> validatedResourceData = validateAndPrepareResource(resourceType, resourceData, useLenientValidation);
            
            // Step 2: Generate ID if not provided
            String resourceId = validatedResourceData.containsKey("id") ? 
                validatedResourceData.get("id").toString() : 
                java.util.UUID.randomUUID().toString();
            
            // Step 3: Set/update resource metadata
            validatedResourceData.put("resourceType", resourceType);
            validatedResourceData.put("id", resourceId);
            
            // Step 4: Convert to JSON for the storage helper
            String resourceJson;
            try {
                resourceJson = objectMapper.writeValueAsString(validatedResourceData);
            } catch (Exception jsonException) {
                logger.error("Failed to convert resource to JSON: {}", jsonException.getMessage());
                throw new RuntimeException("JSON conversion failed: " + jsonException.getMessage(), jsonException);
            }
            
            // Step 5: Use storage helper to process and store with audit metadata
            Map<String, Object> storageResult = storageHelper.processAndStoreResource(resourceJson, cluster, bucketName, "CREATE");
            
            if (!(Boolean) storageResult.get("success")) {
                throw new RuntimeException("Storage failed: " + storageResult.get("error"));
            }
            
            // Step 6: Create response
            Map<String, Object> response = new HashMap<>();
            response.put("id", storageResult.get("resourceId"));
            response.put("resourceType", storageResult.get("resourceType"));
            response.put("created", getCurrentFhirTimestamp());
            response.put("location", "/api/fhir-test/" + bucketName + "/" + resourceType + "/" + storageResult.get("resourceId"));
            response.put("status", "created");
            response.put("operation", "UPSERT");
            response.put("validationStatus", "passed");
            response.put("auditInfo", "Added comprehensive audit trail");
            response.put("documentKey", storageResult.get("documentKey"));
            
            logger.info("✅ Successfully created validated {} with ID: {}", resourceType, storageResult.get("resourceId"));
            return response;

        } catch (Exception e) {
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Create resource using N1QL INSERT with FHIR validation (legacy method for reference)
     */
    public Map<String, Object> createResourceLegacy(String resourceType, String connectionName, 
                                            String bucketName, Map<String, Object> resourceData) {
        try {
            logger.info("🚀 Creating FHIR {} resource with validation (legacy method)", resourceType);
            
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                throw new RuntimeException("No active connection found: " + connectionName);
            }

            // Step 1: Validate and prepare the FHIR resource
            Map<String, Object> validatedResourceData = validateAndPrepareResource(resourceType, resourceData);
            
            // Step 2: Generate ID if not provided (after validation to ensure ID field is proper)
            String resourceId = validatedResourceData.containsKey("id") ? 
                validatedResourceData.get("id").toString() : 
                java.util.UUID.randomUUID().toString();
            
            // Step 3: Set/update resource metadata
            validatedResourceData.put("resourceType", resourceType);
            validatedResourceData.put("id", resourceId);
            
            // Step 4: Convert to JSON for the storage helper
            String resourceJson;
            try {
                resourceJson = objectMapper.writeValueAsString(validatedResourceData);
            } catch (Exception jsonException) {
                logger.error("Failed to convert resource to JSON: {}", jsonException.getMessage());
                throw new RuntimeException("JSON conversion failed: " + jsonException.getMessage(), jsonException);
            }
            
            // Step 5: Use storage helper to process and store with audit metadata
            Map<String, Object> storageResult = storageHelper.processAndStoreResource(resourceJson, cluster, bucketName, "CREATE");
            
            if (!(Boolean) storageResult.get("success")) {
                throw new RuntimeException("Storage failed: " + storageResult.get("error"));
            }
            
            // Step 6: Create response
            Map<String, Object> response = new HashMap<>();
            response.put("id", storageResult.get("resourceId"));
            response.put("resourceType", storageResult.get("resourceType"));
            response.put("created", getCurrentFhirTimestamp());
            response.put("location", "/api/fhir-test/" + bucketName + "/" + resourceType + "/" + storageResult.get("resourceId"));
            response.put("status", "created");
            response.put("operation", "UPSERT");
            response.put("validationStatus", "passed");
            response.put("auditInfo", "Added comprehensive audit trail");
            response.put("documentKey", storageResult.get("documentKey"));
            
            logger.info("✅ Successfully created validated {} with ID: {}", resourceType, storageResult.get("resourceId"));
            return response;

        } catch (Exception e) {
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Create resource from JSON string with FHIR validation - handles both Bundles and individual resources
     */
    public Map<String, Object> createResourceFromJson(String resourceType, String connectionName, 
                                                     String bucketName, String resourceJson) {
        try {
            logger.info("🚀 Processing FHIR resource from JSON with validation");
            
            // Parse JSON to determine if it's a Bundle or individual resource
            JsonObject jsonObject = JsonObject.fromJson(resourceJson);
            String actualResourceType = jsonObject.getString("resourceType");
            
            if ("Bundle".equals(actualResourceType)) {
                // Handle Bundle using Bundle processor
                return processBundleResource(connectionName, bucketName, resourceJson);
            } else {
                // Handle individual resource using existing method
                Map<String, Object> resourceData = jsonObject.toMap();
                return createResource(actualResourceType, connectionName, bucketName, resourceData);
            }
            
        } catch (Exception e) {
            logger.error("Failed to create resource from JSON: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Process a Bundle resource using the Bundle processor with strict validation
     */
    private Map<String, Object> processBundleResource(String connectionName, String bucketName, String bundleJson) {
        return processBundleResource(connectionName, bucketName, bundleJson, false);
    }
    
    /**
     * Process a Bundle resource using the Bundle processor with configurable validation
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name  
     * @param bundleJson Bundle JSON string
     * @param useLenientValidation If true, uses basic FHIR validation; if false, uses strict US Core validation
     */
    private Map<String, Object> processBundleResource(String connectionName, String bucketName, String bundleJson, boolean useLenientValidation) {
        try {
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            logger.info("🔄 Processing FHIR Bundle with transaction support using {} validation", validationType);
            
            // Use provided connection or default
            connectionName = connectionName != null ? connectionName : getDefaultConnection();
            bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
            
            // Process Bundle using the Bundle processor with specified validation
            org.hl7.fhir.r4.model.Bundle responseBundle = bundleProcessor.processBundleTransaction(bundleJson, connectionName, bucketName, useLenientValidation);
            
            // Convert response Bundle back to Map for consistent API response
            String responseBundleJson = jsonParser.encodeResourceToString(responseBundle);
            JsonObject responseBundleObject = JsonObject.fromJson(responseBundleJson);
            Map<String, Object> bundleData = responseBundleObject.toMap();
            
            // Create summary response
            Map<String, Object> response = new HashMap<>();
            response.put("id", responseBundle.getId());
            response.put("resourceType", "Bundle");
            response.put("created", getCurrentFhirTimestamp());
            response.put("status", "processed");
            response.put("operation", "BUNDLE_TRANSACTION");
            response.put("entryCount", responseBundle.getEntry().size());
            response.put("bundleType", responseBundle.getType().name());
            response.put("validationStatus", "passed (" + validationType + ")");
            response.put("auditInfo", "Bundle processed with comprehensive audit trail");
            response.put("bundleResponse", bundleData);
            
            logger.info("✅ Successfully processed Bundle with {} entries using {} validation", responseBundle.getEntry().size(), validationType);
            return response;
            
        } catch (Exception e) {
            logger.error("Failed to process Bundle: {}", e.getMessage());
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("resourceType", "Bundle");
            errorResponse.put("status", "error");
            errorResponse.put("operation", "BUNDLE_TRANSACTION");
            errorResponse.put("message", "Bundle processing failed: " + e.getMessage());
            errorResponse.put("timestamp", getCurrentFhirTimestamp());
            
            throw new RuntimeException("Bundle processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create resource from JSON string with FHIR validation (original method - keeping for backward compatibility)
     */
    public Map<String, Object> createResourceFromJsonLegacy(String resourceType, String connectionName, 
                                                     String bucketName, String resourceJson) {
        try {
            logger.info("🚀 Creating FHIR {} resource from JSON with validation", resourceType);
            
            // Parse JSON to Map
            JsonObject jsonObject = JsonObject.fromJson(resourceJson);
            Map<String, Object> resourceData = jsonObject.toMap();
            
            // Call the main create method
            return createResource(resourceType, connectionName, bucketName, resourceData);
            
        } catch (Exception e) {
            logger.error("Failed to create {} from JSON: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Validate FHIR resource using HAPI FHIR validator (uses strict US Core validation by default)
     */
    public ValidationResult validateFhirResource(String resourceJson, String resourceType) {
        return validateFhirResource(resourceJson, resourceType, false);
    }
    
    /**
     * Validate FHIR resource using HAPI FHIR validator with configurable validation strictness
     * @param resourceJson JSON string of the FHIR resource
     * @param resourceType Resource type name
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     */
    public ValidationResult validateFhirResource(String resourceJson, String resourceType, boolean useLenientValidation) {
        try {
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            logger.info("🔍 Validating FHIR {} resource with {} validation", resourceType, validationType);
            
            // Parse JSON to FHIR resource
            IBaseResource resource = jsonParser.parseResource(resourceJson);
            
            // Choose validator based on validation type
            FhirValidator validator = useLenientValidation ? basicFhirValidator : fhirValidator;
            
            // Validate the resource
            ValidationResult result = validator.validateWithResult(resource);
            
            // Filter out INFORMATION level messages
            List<SingleValidationMessage> filteredMessages = result
                .getMessages()
                .stream()
                .filter(msg -> msg.getSeverity() != ResultSeverityEnum.INFORMATION)
                .collect(Collectors.toList());
            
            // Create new ValidationResult with filtered messages
            ValidationResult validationResult = new ValidationResult(result.getContext(), filteredMessages);
            
            if (validationResult.isSuccessful()) {
                logger.info("✅ FHIR {} validation passed ({})", resourceType, validationType);
            } else {
                logger.warn("❌ FHIR {} validation failed with {} significant issues ({})", 
                    resourceType, validationResult.getMessages().size(), validationType);
                
                // Log validation errors (without full stack trace)
                validationResult.getMessages().forEach(message -> {
                    logger.warn("   {} - {}: {}", 
                        message.getSeverity(), 
                        message.getLocationString(), 
                        message.getMessage());
                });
            }
            
            return validationResult;
            
        } catch (Exception e) {
            // Log parsing errors more cleanly (without full stack trace for common validation errors)
            if (e.getMessage().contains("Invalid date/time format") || 
                e.getMessage().contains("Invalid attribute value") ||
                e.getMessage().contains("HAPI-")) {
                logger.warn("⚠️ FHIR {} parsing failed: {}", resourceType, e.getMessage());
            } else {
                logger.error("Failed to validate FHIR {} resource: {}", resourceType, e.getMessage(), e);
            }
            throw new RuntimeException("FHIR validation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Validate and prepare FHIR resource data for storage using strict US Core validation (default)
     */
    private Map<String, Object> validateAndPrepareResource(String resourceType, Map<String, Object> resourceData) {
        return validateAndPrepareResource(resourceType, resourceData, false);
    }
    
    /**
     * Validate and prepare FHIR resource data for storage with configurable validation
     * @param resourceType FHIR resource type
     * @param resourceData Resource data as Map
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     */
    private Map<String, Object> validateAndPrepareResource(String resourceType, Map<String, Object> resourceData, boolean useLenientValidation) {
        try {
            // Convert Map to JSON string for HAPI validation
            String resourceJson = JsonObject.from(resourceData).toString();
            
            // Validate with HAPI FHIR using specified validation mode
            ValidationResult validationResult = validateFhirResource(resourceJson, resourceType, useLenientValidation);
            
            if (!validationResult.isSuccessful()) {
                StringBuilder errorMsg = new StringBuilder("FHIR validation failed:\n");
                validationResult.getMessages().forEach(message -> {
                    errorMsg.append(String.format("- %s at %s: %s\n", 
                        message.getSeverity(), 
                        message.getLocationString(), 
                        message.getMessage()));
                });
                throw new RuntimeException(errorMsg.toString());
            }
            
            // Parse back to ensure proper FHIR structure (this is where the magic happens)
            IBaseResource validatedResource = jsonParser.parseResource(resourceJson);
            String validatedJson = jsonParser.encodeResourceToString(validatedResource);
            
            // Convert back to Map for Couchbase storage
            JsonObject validatedJsonObject = JsonObject.fromJson(validatedJson);
            return validatedJsonObject.toMap();
            
        } catch (Exception e) {
            logger.error("Failed to validate and prepare FHIR {} resource: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Validate FHIR resource without creating it using strict US Core validation (default)
     */
    public Map<String, Object> validateResourceOnly(String resourceType, Map<String, Object> resourceData) {
        return validateResourceOnly(resourceType, resourceData, false);
    }
    
    /**
     * Validate FHIR resource without creating it with configurable validation (useful for testing)
     * @param resourceType FHIR resource type
     * @param resourceData Resource data as Map
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     */
    public Map<String, Object> validateResourceOnly(String resourceType, Map<String, Object> resourceData, boolean useLenientValidation) {
        try {
            String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            logger.info("🔍 Validating FHIR {} resource only with {} validation", resourceType, validationType);
            
            // Convert Map to JSON string for HAPI validation
            String resourceJson = JsonObject.from(resourceData).toString();
            
            // Validate with HAPI FHIR using specified validation mode
            ValidationResult validationResult = validateFhirResource(resourceJson, resourceType, useLenientValidation);
            
            // Create response
            Map<String, Object> response = new HashMap<>();
            response.put("resourceType", resourceType);
            response.put("valid", validationResult.isSuccessful());
            response.put("timestamp", getCurrentFhirTimestamp());
            
            if (validationResult.isSuccessful()) {
                response.put("status", "validation_passed");
                response.put("message", "FHIR resource is valid");
            } else {
                response.put("status", "validation_failed");
                response.put("errorCount", validationResult.getMessages().size());
                
                List<Map<String, Object>> errors = new ArrayList<>();
                validationResult.getMessages().forEach(message -> {
                    Map<String, Object> error = new HashMap<>();
                    error.put("severity", message.getSeverity().name());
                    error.put("location", message.getLocationString());
                    error.put("message", message.getMessage());
                    errors.add(error);
                });
                response.put("errors", errors);
            }
            
            return response;
            
        } catch (Exception e) {
            // Handle parsing errors gracefully - don't log full stack trace for validation errors
            String errorMessage = e.getMessage();
            if (errorMessage.contains("HAPI-") || errorMessage.contains("Invalid")) {
                logger.warn("⚠️ FHIR {} validation failed during parsing: {}", resourceType, errorMessage);
            } else {
                logger.error("Failed to validate FHIR {} resource: {}", resourceType, errorMessage);
            }
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("resourceType", resourceType);
            errorResponse.put("valid", false);
            errorResponse.put("status", "validation_error");
            errorResponse.put("message", "Validation failed: " + errorMessage);
            errorResponse.put("timestamp", getCurrentFhirTimestamp());
            
            return errorResponse;
        }
    }

    // Helper methods
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }

    private String getCurrentFhirTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }
}
