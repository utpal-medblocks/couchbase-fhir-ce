package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FHIRBundleProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(FHIRBundleProcessingService.class);

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirValidator fhirValidator;
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FHIRAuditService auditService;

    // Default connection and bucket names
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    @PostConstruct
    private void init() {
        logger.info("🚀 FHIR Bundle Processing Service initialized");
    }

    /**
     * Process a FHIR Bundle transaction - extract, validate, resolve references, and prepare for insertion
     */
    public Map<String, Object> processBundleTransaction(String bundleJson, String connectionName, String bucketName) {
        try {
            logger.info("🔄 Processing FHIR Bundle transaction");
            
            // Step 1: Parse Bundle
            Bundle bundle = (Bundle) jsonParser.parseResource(bundleJson);
            logger.info("📦 Parsed Bundle with {} entries", bundle.getEntry().size());

            // Step 2: Validate Bundle structure
            ValidationResult bundleValidation = validateBundle(bundle);
            if (!bundleValidation.isSuccessful()) {
                throw new RuntimeException("Bundle validation failed: " + bundleValidation.getMessages());
            }
            logger.info("✅ Bundle structure validation passed");

            // Step 3: Extract all resources from Bundle using HAPI utility
            List<IBaseResource> allResources = BundleUtil.toListOfResources(fhirContext, bundle);
            logger.info("📋 Extracted {} resources from Bundle", allResources.size());

            // Step 4: Process entries sequentially with proper UUID resolution
            List<ProcessedEntry> processedEntries = processEntriesSequentially(bundle, connectionName, bucketName);

            // Step 5: Create response from processed entries
            return createBundleProcessingResponse(processedEntries);

        } catch (Exception e) {
            logger.error("❌ Failed to process Bundle transaction: {}", e.getMessage(), e);
            throw new RuntimeException("Bundle processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Process Bundle entries sequentially with proper UUID resolution
     */
    private List<ProcessedEntry> processEntriesSequentially(Bundle bundle, String connectionName, String bucketName) {
        logger.info("🔄 Processing Bundle entries sequentially");
        
        connectionName = connectionName != null ? connectionName : getDefaultConnection();
        bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;
        
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new RuntimeException("No active connection found: " + connectionName);
        }
        
        // Step 1: Build UUID mapping for all entries first
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);
        
        // Step 2: Process each entry in order
        List<ProcessedEntry> processedEntries = new ArrayList<>();
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        
        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            
            try {
                // Step 2a: Resolve UUID references in this resource
                resolveUuidReferencesInResource(resource, uuidToIdMapping);
                
                // Step 2b: Validate the resource
                ValidationResult validation = fhirValidator.validateWithResult(resource);
                if (!validation.isSuccessful()) {
                    logger.warn("⚠️ Validation failed for {}: {}", resourceType, validation.getMessages());
                    // Continue processing even if validation fails (configurable behavior)
                }
                
                // Step 2c: Add audit information
                auditService.addAuditInfoToMeta(resource, auditInfo, "CREATE");
                
                // Step 2d: Prepare for insertion
                String resourceId = resource.getIdElement().getIdPart();
                String documentKey = resourceType + "/" + resourceId;
                
                // Step 2e: Insert into Couchbase
                insertResourceIntoCouchbase(cluster, bucketName, resourceType, documentKey, resource);
                
                // Step 2f: Create response entry
                Bundle.BundleEntryComponent responseEntry = createResponseEntry(resource, resourceType);
                
                processedEntries.add(ProcessedEntry.success(resourceType, resourceId, documentKey, responseEntry));
                logger.info("✅ Successfully processed {}/{}", resourceType, resourceId);
                
            } catch (Exception e) {
                logger.error("❌ Failed to process {} entry: {}", resourceType, e.getMessage());
                processedEntries.add(ProcessedEntry.failed("Failed to process " + resourceType + ": " + e.getMessage()));
            }
        }
        
        return processedEntries;
    }
    
    /**
     * Build UUID mapping for all entries in the Bundle
     */
    private Map<String, String> buildUuidMapping(Bundle bundle) {
        Map<String, String> uuidToIdMapping = new HashMap<>();
        
        logger.info("🔄 Building UUID mapping for Bundle with {} entries", bundle.getEntry().size());
        
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            
            logger.info("📝 Processing entry - ResourceType: {}, FullUrl: {}, Initial ID: {}", 
                resourceType, entry.getFullUrl(), resource.getId());
            
            // Generate ID if not present OR extract meaningful part from urn:uuid
            if (resource.getId() == null || resource.getId().isEmpty()) {
                String generatedId = UUID.randomUUID().toString();
                resource.setId(generatedId);
                logger.info("🆔 Generated UUID ID for {}: {}", resourceType, generatedId);
            } else if (resource.getId().startsWith("urn:uuid:")) {
                // Extract the meaningful part after "urn:uuid:"
                String oldId = resource.getId();
                String extractedId = oldId.substring("urn:uuid:".length());
                resource.setId(extractedId);
                logger.info("🔄 Extracted ID from urn:uuid for {}: {} → {}", resourceType, oldId, extractedId);
            } else {
                logger.info("✅ Using existing ID for {}: {}", resourceType, resource.getId());
            }
            
            // Map urn:uuid to actual ID
            if (entry.getFullUrl() != null && entry.getFullUrl().startsWith("urn:uuid:")) {
                String uuid = entry.getFullUrl();
                String actualId = resource.getIdElement().getIdPart();
                String mappedReference = resourceType + "/" + actualId;
                uuidToIdMapping.put(uuid, mappedReference);
                logger.info("🔗 UUID mapping: {} → {}", uuid, mappedReference);
            } else {
                logger.info("📝 No UUID mapping for {} (fullUrl: {})", resourceType, entry.getFullUrl());
            }
        }
        
        logger.info("📊 Final UUID mapping: {}", uuidToIdMapping);
        return uuidToIdMapping;
    }

    /**
     * Resolve urn:uuid references in a single resource
     */
    private void resolveUuidReferencesInResource(Resource resource, Map<String, String> uuidToIdMapping) {
        FhirTerser terser = fhirContext.newTerser();
        String resourceType = resource.getResourceType().name();
        
        // Find all Reference fields in the resource
        List<Reference> references = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);
        
        logger.info("🔍 Found {} references in {}", references.size(), resourceType);
        
        for (Reference reference : references) {
            String originalRef = reference.getReference();
            logger.info("🔍 Processing reference: {}", originalRef);
            
            if (originalRef != null && originalRef.contains("urn:uuid:")) {
                // Handle both "urn:uuid:xxx" and "ResourceType/urn:uuid:xxx" formats
                String uuid = null;
                if (originalRef.startsWith("urn:uuid:")) {
                    uuid = originalRef; // Direct UUID reference
                } else if (originalRef.contains("/urn:uuid:")) {
                    // Extract just the urn:uuid part
                    int uuidIndex = originalRef.indexOf("urn:uuid:");
                    uuid = originalRef.substring(uuidIndex);
                }
                
                if (uuid != null) {
                    String actualReference = uuidToIdMapping.get(uuid);
                    
                    if (actualReference != null) {
                        reference.setReference(actualReference);
                        logger.info("🔗 Resolved reference in {}: {} → {}", resourceType, originalRef, actualReference);
                    } else {
                        logger.warn("⚠️ Could not resolve UUID reference: {} (uuid: {})", originalRef, uuid);
                        logger.warn("⚠️ Available mappings: {}", uuidToIdMapping.keySet());
                    }
                } else {
                    logger.warn("⚠️ Could not extract UUID from reference: {}", originalRef);
                }
            } else {
                logger.info("📝 Non-UUID reference (skipping): {}", originalRef);
            }
        }
    }
    
    /**
     * Insert a single resource into Couchbase
     */
    private void insertResourceIntoCouchbase(Cluster cluster, String bucketName, String resourceType, 
                                           String documentKey, Resource resource) {
        // Ensure proper meta information - keep minimal
        Meta meta = resource.getMeta();
        if (meta == null) {
            meta = new Meta();
            resource.setMeta(meta);
        }
        meta.setVersionId("1");
        // Skip profile to keep meta minimal
        
        // Convert to JSON and then to Map for Couchbase
        String resourceJson = jsonParser.encodeResourceToString(resource);
        Map<String, Object> resourceMap = JsonObject.fromJson(resourceJson).toMap();
        
        // UPSERT into appropriate collection
        String sql = String.format(
            "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
            bucketName, DEFAULT_SCOPE, resourceType, documentKey, 
            JsonObject.from(resourceMap).toString()
        );
        
        cluster.query(sql);
        logger.info("✅ Upserted {}/{} into collection", resourceType, resource.getIdElement().getIdPart());
    }
    
    /**
     * Create a response entry for Bundle transaction response
     */
    private Bundle.BundleEntryComponent createResponseEntry(Resource resource, String resourceType) {
        Bundle.BundleEntryComponent responseEntry = new Bundle.BundleEntryComponent();
        
        // Set the resource in response
        responseEntry.setResource(resource);
        
        // Set response details
        Bundle.BundleEntryResponseComponent response = new Bundle.BundleEntryResponseComponent();
        response.setStatus("201 Created");
        response.setLocation(resourceType + "/" + resource.getIdElement().getIdPart());
        responseEntry.setResponse(response);
        
        return responseEntry;
    }





    /**
     * Validate Bundle structure
     */
    private ValidationResult validateBundle(Bundle bundle) {
        return fhirValidator.validateWithResult(bundle);
    }

    /**
     * Create comprehensive response
     */
    private Map<String, Object> createBundleProcessingResponse(List<ProcessedEntry> processedEntries) {
        Map<String, Object> response = new HashMap<>();
        
        // Overall summary
        long successCount = processedEntries.stream().mapToLong(entry -> entry.isSuccess() ? 1 : 0).sum();
        response.put("status", successCount == processedEntries.size() ? "success" : "partial");
        response.put("timestamp", getCurrentFhirTimestamp());
        response.put("bundleProcessed", true);
        
        // Resource type summary
        Map<String, Integer> resourceCounts = processedEntries.stream()
            .filter(ProcessedEntry::isSuccess)
            .collect(Collectors.groupingBy(ProcessedEntry::getResourceType, 
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)));
        response.put("resourcesByType", resourceCounts);
        
        // Processing summary
        response.put("processingSummary", Map.of(
            "total", processedEntries.size(),
            "successful", successCount,
            "failed", processedEntries.size() - successCount
        ));
        
        // Detailed results
        List<Map<String, Object>> detailedResults = processedEntries.stream()
            .map(entry -> {
                Map<String, Object> result = new HashMap<>();
                result.put("success", entry.isSuccess());
                if (entry.isSuccess()) {
                    result.put("resourceType", entry.getResourceType());
                    result.put("resourceId", entry.getResourceId());
                    result.put("documentKey", entry.getDocumentKey());
                } else {
                    result.put("errorMessage", entry.getErrorMessage());
                }
                return result;
            })
            .toList();
        response.put("detailedResults", detailedResults);
        
        return response;
    }

    private String getCurrentFhirTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }



    /**
     * Create validation failure response
     */


    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}