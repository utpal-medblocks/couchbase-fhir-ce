package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FHIRTestCreateService {

    private static final Logger logger = LoggerFactory.getLogger(FHIRTestCreateService.class);

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirContext fhirContext;  // ✅ Inject the configured context
    
    @Autowired
    private FhirValidator fhirValidator;  // ✅ Inject the configured validator
    
    @Autowired
    private IParser jsonParser;       // ✅ Inject the configured parser
    
    @Autowired
    private FHIRAuditService auditService;  // ✅ Inject the audit service

    // Default connection and bucket names if not provided
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    public FHIRTestCreateService() {
        // Empty - Spring will inject dependencies
    }
    
    @PostConstruct
    private void init() {
        logger.info("Initialized FHIR R4 context and validator for create operations");
    }

    /**
     * Create resource using N1QL INSERT with FHIR validation
     */
    public Map<String, Object> createResource(String resourceType, String connectionName, 
                                            String bucketName, Map<String, Object> resourceData) {
        try {
            logger.info("🚀 Creating FHIR {} resource with validation", resourceType);
            
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
                UUID.randomUUID().toString();
            
            // Step 3: Set/update resource metadata
            validatedResourceData.put("resourceType", resourceType);
            validatedResourceData.put("id", resourceId);
            
            // Step 4: Convert to FHIR resource and add audit information
            String resourceJson = JsonObject.from(validatedResourceData).toString();
            IBaseResource fhirResource = jsonParser.parseResource(resourceJson);
            
            // Add comprehensive audit information
            auditService.addAuditInfoToMeta(fhirResource, auditService.getCurrentUserId(), "CREATE");
            
            // Convert back to JSON with audit info
            String auditedResourceJson = jsonParser.encodeResourceToString(fhirResource);
            Map<String, Object> auditedResourceData = JsonObject.fromJson(auditedResourceJson).toMap();
            
            // Add additional meta information - keep minimal
            Map<String, Object> meta = (Map<String, Object>) auditedResourceData.get("meta");
            if (meta == null) {
                meta = new HashMap<>();
                auditedResourceData.put("meta", meta);
            }
            meta.put("versionId", "1");
            // Skip profile to keep meta minimal

            // Step 5: Create document key with ResourceType::id format
            String documentKey = resourceType + "/" + resourceId;

            // Step 6: Build N1QL UPSERT query (changed from INSERT to UPSERT)
            String sql = String.format(
                "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey, 
                JsonObject.from(auditedResourceData).toString()
            );

            logger.info("Executing N1QL UPSERT for validated {}: {}", resourceType, resourceId);
            
            cluster.query(sql);
            
            // Step 7: Create response
            Map<String, Object> response = new HashMap<>();
            response.put("id", resourceId);
            response.put("resourceType", resourceType);
            response.put("created", getCurrentFhirTimestamp());
            response.put("location", "/api/fhir-test/" + bucketName + "/" + resourceType + "/" + resourceId);
            response.put("status", "created");
            response.put("operation", "UPSERT");
            response.put("validationStatus", "passed");
            response.put("auditInfo", "Added comprehensive audit trail");
            
            logger.info("✅ Successfully upserted validated {} with ID: {}", resourceType, resourceId);
            return response;

        } catch (Exception e) {
            logger.error("Failed to create {}: {}", resourceType, e.getMessage());
            throw e;
        }
    }

    /**
     * Create resource from JSON string with FHIR validation
     */
    public Map<String, Object> createResourceFromJson(String resourceType, String connectionName, 
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
     * Validate FHIR resource using HAPI FHIR validator
     */
    public ValidationResult validateFhirResource(String resourceJson, String resourceType) {
        try {
            logger.info("🔍 Validating FHIR {} resource", resourceType);
            
            // Parse JSON to FHIR resource
            IBaseResource resource = jsonParser.parseResource(resourceJson);
            
            // Validate the resource
            ValidationResult validationResult = fhirValidator.validateWithResult(resource);
            
            if (validationResult.isSuccessful()) {
                logger.info("✅ FHIR {} validation passed", resourceType);
            } else {
                logger.warn("❌ FHIR {} validation failed with {} issues", 
                    resourceType, validationResult.getMessages().size());
                
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
     * Validate and prepare FHIR resource data for storage
     */
    private Map<String, Object> validateAndPrepareResource(String resourceType, Map<String, Object> resourceData) {
        try {
            // Convert Map to JSON string for HAPI validation
            String resourceJson = JsonObject.from(resourceData).toString();
            
            // Validate with HAPI FHIR
            ValidationResult validationResult = validateFhirResource(resourceJson, resourceType);
            
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
     * Validate FHIR resource without creating it (useful for testing)
     */
    public Map<String, Object> validateResourceOnly(String resourceType, Map<String, Object> resourceData) {
        try {
            logger.info("🔍 Validating FHIR {} resource only", resourceType);
            
            // Convert Map to JSON string for HAPI validation
            String resourceJson = JsonObject.from(resourceData).toString();
            
            // Validate with HAPI FHIR
            ValidationResult validationResult = validateFhirResource(resourceJson, resourceType);
            
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
