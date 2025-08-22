package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;


import java.util.*;
import java.util.stream.Collectors;

@Service
public class FhirBundleProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(FhirBundleProcessingService.class);

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private FhirContext fhirContext;

    @Autowired
    private FhirValidator fhirValidator;  // Primary US Core validator

    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator basicFhirValidator;  // Basic validator for sample data

    @Autowired
    private IParser jsonParser;

    @Autowired
    private FhirAuditService auditService;

    // Default connection and bucket names
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    @PostConstruct
    private void init() {
        logger.info("🚀 FHIR Bundle Processing Service initialized");
        // Configure parser for optimal performance - critical for bundle processing
        jsonParser.setPrettyPrint(false);                    // ✅ No formatting overhead
        jsonParser.setStripVersionsFromReferences(false);    // Skip processing
        jsonParser.setOmitResourceId(false);                 // Keep IDs as-is
        jsonParser.setSummaryMode(false);                    // Full resources
        jsonParser.setOverrideResourceIdWithBundleEntryFullUrl(false); // Big performance gain for bundles

        // Context-level optimizations
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false);

        logger.info("✅ Bundle Processing Service optimized for high-performance transactions");
    }

    /**
     * Process a FHIR Bundle transaction with strict US Core validation (default)
     * @deprecated Use processBundleTransaction(String, String, String, FhirBucketConfig) instead
     */
    @Deprecated
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName) {
        throw new UnsupportedOperationException("This method is deprecated. Use the version that accepts FhirBucketConfig.");
    }
    /**
     * Process a FHIR Bundle transaction with configurable validation (deprecated - use config object version)
     * @param bundleJson Bundle JSON string
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name
     * @param useLenientValidation If true, uses basic FHIR validation instead of strict US Core validation
     * @deprecated Use processBundleTransaction(String, String, String, FhirBucketConfig) instead
     */
    @Deprecated
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName, boolean useLenientValidation) {
        // Create a temporary config object for backward compatibility
        throw new UnsupportedOperationException("This method is deprecated. Use the version that accepts FhirBucketConfig.");
    }
    /**
     * Process a FHIR Bundle transaction with bucket-specific validation configuration
     * @param bundleJson Bundle JSON string
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name
     * @param bucketConfig Complete bucket validation configuration
     */
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName,
                                           com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        try {
            // Extract validation settings from bucket config
            boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
            boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
            boolean enforceUSCore = bucketConfig.isEnforceUSCore();
            boolean allowUnknownElements = bucketConfig.isAllowUnknownElements();
            boolean terminologyChecks = bucketConfig.isTerminologyChecks();
            
            // Build validation description
            String validationType;
            if (skipValidation) {
                validationType = "NONE (disabled)";
            } else {
                StringBuilder desc = new StringBuilder();
                desc.append(useLenientValidation ? "lenient" : "strict");
                desc.append(" (").append(enforceUSCore ? "US Core 6.1.0" : "basic FHIR R4");
                if (allowUnknownElements) desc.append(", allow unknown");
                if (terminologyChecks) desc.append(", terminology checks");
                desc.append(")");
                validationType = desc.toString();
            }
            logger.info("🔄 Processing FHIR Bundle transaction with {} validation", validationType);
            // Step 1: Parse Bundle
            Bundle bundle = (Bundle) jsonParser.parseResource(bundleJson);
            logger.info("📦 Parsed Bundle with {} entries", bundle.getEntry().size());

            // Step 2: Validate Bundle structure (skip if requested for performance)
            if (!skipValidation) {
                ValidationResult bundleValidation = validateBundle(bundle, bucketConfig);
                if (!bundleValidation.isSuccessful()) {
                    logger.error("❌ Bundle validation failed with {} errors", bundleValidation.getMessages().size());
                    bundleValidation.getMessages().forEach(msg ->
                            logger.error("   {} - {}: {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                    throw new RuntimeException("Bundle validation failed - see logs for details");
                }
                logger.info("✅ Bundle structure validation passed ({})", validationType);
            } else {
                logger.info("⚡ Bundle structure validation SKIPPED for performance");
            }

            // Step 3: Extract all resources from Bundle using HAPI utility
            List<IBaseResource> allResources = BundleUtil.toListOfResources(fhirContext, bundle);
            logger.info("📋 Extracted {} resources from Bundle", allResources.size());

            // Step 4: Process entries sequentially with proper UUID resolution and ACID transaction support
            List<ProcessedEntry> processedEntries;
            
            // Use Couchbase Server transactions for BUNDLE TRANSACTION types (not for BATCH)
            if (bundle.getType() == Bundle.BundleType.TRANSACTION) {
                logger.info("🔒 Starting Couchbase Server TRANSACTION for Bundle processing");
                processedEntries = processEntriesWithTransaction(bundle, connectionName, bucketName, bucketConfig);
            } else {
                logger.info("📦 Processing Bundle as BATCH (no transaction wrapper)");
                processedEntries = processEntriesSequentially(bundle, connectionName, bucketName, bucketConfig);
            }

            // Step 5: Create proper FHIR transaction-response Bundle
            return createTransactionResponseBundle(processedEntries, bundle.getType());

        } catch (Exception e) {
            String cleanMessage = extractCleanErrorMessage(e);
            logger.error("❌ Failed to process Bundle transaction: {}", cleanMessage);
            if (isCouchbaseError(e)) {
                logger.debug("❌ Couchbase error details:", e);
            } else {
                logger.debug("❌ Full stack trace:", e);
            }
            throw new RuntimeException(cleanMessage);
        }
    }

    /**
     * Process Bundle entries within a Couchbase Server transaction for ACID properties
     */
    private List<ProcessedEntry> processEntriesWithTransaction(Bundle bundle, String connectionName, String bucketName, 
                                                              com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
        logger.info("🔒 Processing Bundle entries with Couchbase Server TRANSACTION (validation: {})", skipValidation ? "SKIPPED" : "ENABLED");

        final String finalConnectionName = connectionName != null ? connectionName : getDefaultConnection();
        final String finalBucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

        Cluster cluster = connectionService.getConnection(finalConnectionName);
        if (cluster == null) {
            throw new RuntimeException("No active connection found: " + finalConnectionName);
        }

        List<ProcessedEntry> processedEntries = new ArrayList<>();
        
        try {
            // Use Couchbase Transactions API for proper ACID guarantees
            logger.info("🚀 Starting Couchbase transaction for Bundle processing");
            
            try {
                // Execute all operations within a single transaction
                cluster.transactions().run((ctx) -> {
                    logger.info("🔄 Executing Bundle operations within transaction context");
                    processEntriesInTransactionContext(ctx, bundle, cluster, finalBucketName, bucketConfig);
                });
                
                logger.info("✅ Transaction committed successfully - Bundle processing complete");
                
                // Create processed entries for response (without re-processing)
                processedEntries = createProcessedEntriesFromBundle(bundle);
                
            } catch (Exception txEx) {
                String cleanMessage = extractCleanErrorMessage(txEx);
                logger.error("❌ FHIR Bundle TRANSACTION failed: {}", cleanMessage);
                logger.debug("❌ Transaction error details:", txEx);
                // ✅ CORRECT: No fallback - FHIR transactions must be atomic (all-or-nothing)
                // POST operations MUST fail if resource exists (409 Conflict)
                // PUT operations should succeed and update existing resources
                // TRANSACTION bundles require strict atomicity - no partial success allowed
                throw new RuntimeException("Bundle TRANSACTION failed (FHIR atomicity required): " + cleanMessage, txEx);
            }
            
        } catch (Exception e) {
            String cleanMessage = extractCleanErrorMessage(e);
            logger.error("❌ Transaction processing failed: {}", cleanMessage);
            if (isCouchbaseError(e)) {
                logger.debug("❌ Couchbase error details:", e);
            } else {
                logger.debug("❌ Full stack trace:", e);
            }
            throw new RuntimeException(cleanMessage);
        }

        return processedEntries;
    }

    /**
     * Process Bundle entries sequentially with proper UUID resolution (without transaction wrapper)
     */
    private List<ProcessedEntry> processEntriesSequentially(Bundle bundle, String connectionName, String bucketName, 
                                                           com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
        logger.info("🔄 Processing Bundle entries sequentially (validation: {})", skipValidation ? "SKIPPED" : "ENABLED");

        connectionName = connectionName != null ? connectionName : getDefaultConnection();
        bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new RuntimeException("No active connection found: " + connectionName);
        }
        
        return processEntriesSequentiallyInternal(bundle, cluster, bucketName, bucketConfig);
    }

    /**
     * Process Bundle entries within a Couchbase transaction context
     */
    private void processEntriesInTransactionContext(com.couchbase.client.java.transactions.TransactionAttemptContext ctx, 
                                                   Bundle bundle, Cluster cluster, String bucketName, 
                                                   com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        logger.info("🔄 Processing {} Bundle entries within transaction", bundle.getEntry().size());
        
        // Step 1: Build UUID mapping for all entries first
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);
        
        // Step 2: Process each entry in transaction context
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        
        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            
            logger.info("🔄 Processing entry {}/{}: {} resource in transaction", i+1, bundle.getEntry().size(), resourceType);
            
            try {
                // Step 2a: Resolve UUID references in this resource
                resolveUuidReferencesInResource(resource, uuidToIdMapping);
                
                // Step 2b: Add audit information
                auditService.addAuditInfoToMeta(resource, auditInfo, "CREATE");
                
                // Step 2c: Prepare for insertion
                String resourceId = resource.getIdElement().getIdPart();
                String documentKey = resourceType + "/" + resourceId;
                
                // Step 2d: Insert into Couchbase using transaction context
                insertResourceInTransaction(ctx, cluster, bucketName, resourceType, documentKey, resource);
                
            } catch (Exception e) {
                logger.error("❌ Failed to process {} resource in transaction: {}", resourceType, e.getMessage());
                throw new RuntimeException("Transaction failed processing " + resourceType + ": " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Internal method to process Bundle entries with a given cluster connection
     * Used by both transaction and non-transaction processing
     */
    private List<ProcessedEntry> processEntriesSequentiallyInternal(Bundle bundle, Cluster cluster, String bucketName, 
                                                                   com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
        
        // Step 1: Build UUID mapping for all entries first
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);

        // Step 2: Process each entry in order
        List<ProcessedEntry> processedEntries = new ArrayList<>();
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        
        logger.info("🔄 Starting to process {} Bundle entries", bundle.getEntry().size());

        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            
            logger.info("🔄 Processing entry {}/{}: {} resource", i+1, bundle.getEntry().size(), resourceType);

            try {
                // Step 2a: Resolve UUID references in this resource
                resolveUuidReferencesInResource(resource, uuidToIdMapping);

                // Step 2b: Validate the resource (skip if requested for performance)
                if (!skipValidation) {
                    // Choose validator based on bucket config
                    FhirValidator validator;
                    boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
                    
                    if (useLenientValidation || !bucketConfig.isEnforceUSCore()) {
                        validator = basicFhirValidator;
                    } else {
                        validator = fhirValidator;
                    }
                    
                    ValidationResult result = validator.validateWithResult(resource);

                    // Filter out INFORMATION level messages
                    List<SingleValidationMessage> filteredMessages = result
                            .getMessages()
                            .stream()
                            .filter(msg -> msg.getSeverity() != ResultSeverityEnum.INFORMATION)
                            .collect(Collectors.toList());

                    ValidationResult validation = new ValidationResult(result.getContext(), filteredMessages);

                    if (!validation.isSuccessful()) {
                        logger.warn("⚠️ Validation failed for {} with {} significant issues", resourceType, validation.getMessages().size());
                        // Continue processing even if validation fails (configurable behavior)
                    }
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
                logger.debug("✅ Successfully processed {}/{}", resourceType, resourceId);

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.error("❌ Failed to process {} entry: {} (Exception: {})", resourceType, errorMessage, e.getClass().getSimpleName(), e);

                // Additional debug logging for the resource that failed
                logger.error("   Resource ID: {}", resource.getId());
                logger.error("   Resource Type: {}", resourceType);
                if (entry.getFullUrl() != null) {
                    logger.error("   FullUrl: {}", entry.getFullUrl());
                }

                processedEntries.add(ProcessedEntry.failed("Failed to process " + resourceType + ": " + errorMessage));
            }
        }

        return processedEntries;
    }

    /**
     * Build UUID mapping for all entries in the Bundle
     */
    private Map<String, String> buildUuidMapping(Bundle bundle) {
        Map<String, String> uuidToIdMapping = new HashMap<>();

        logger.debug("🔄 Building UUID mapping for Bundle with {} entries", bundle.getEntry().size());

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();

            logger.debug("📝 Processing entry - ResourceType: {}, FullUrl: {}, Initial ID: {}",
                    resourceType, entry.getFullUrl(), resource.getId());

            String actualResourceId;

            // Extract meaningful ID from urn:uuid if present
            if (entry.getFullUrl() != null && entry.getFullUrl().startsWith("urn:uuid:")) {
                String uuidFullUrl = entry.getFullUrl(); // "urn:uuid:org1"
                actualResourceId = extractIdFromUuid(uuidFullUrl); // "org1"
                logger.debug("🆔 Extracted ID from UUID: {} → {}", uuidFullUrl, actualResourceId);
                // Map the full urn:uuid to the resource reference
                String mappedReference = resourceType + "/" + actualResourceId; // "Organization/org1"
                uuidToIdMapping.put(uuidFullUrl, mappedReference);
                logger.debug("🔗 UUID mapping: {} → {}", uuidFullUrl, mappedReference);
            } else {
                // Generate ID for resources without urn:uuid
                actualResourceId = generateResourceId(resourceType);
                logger.debug("🆔 Generated new ID for {}: {}", resourceType, actualResourceId);
            }

            // Set the actual ID on the resource
            resource.setId(actualResourceId);
        }

        logger.debug("📊 Final UUID mapping: {}", uuidToIdMapping);
        return uuidToIdMapping;
    }

    /**
     * Extract meaningful ID from urn:uuid format
     */
    private String extractIdFromUuid(String uuidFullUrl) {
        if (uuidFullUrl.startsWith("urn:uuid:")) {
            String extracted = uuidFullUrl.substring("urn:uuid:".length());
            // Validate that it's a reasonable ID (optional)
            if (isValidResourceId(extracted)) {
                return extracted;
            } else {
                // Fallback to generated ID if the UUID part isn't suitable
                logger.warn("⚠️ UUID part '{}' not suitable as resource ID, generating new one", extracted);
                return UUID.randomUUID().toString();
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Check if extracted ID is valid for use as resource ID
     */
    private boolean isValidResourceId(String id) {
        // FHIR ID rules: length 1-64, [A-Za-z0-9\-\.]{1,64}
        return id != null &&
                id.length() >= 1 &&
                id.length() <= 64 &&
                id.matches("[A-Za-z0-9\\-\\.]+");
    }

    /**
     * Generate a new resource ID
     */
    private String generateResourceId(String resourceType) {
        return UUID.randomUUID().toString();
    }

    /**
     * Resolve urn:uuid references in a single resource
     */
    private void resolveUuidReferencesInResource(Resource resource, Map<String, String> uuidToIdMapping) {
        FhirTerser terser = fhirContext.newTerser();
        String resourceType = resource.getResourceType().name();

        // Find all Reference fields in the resource
        List<Reference> references = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

        logger.debug("🔍 Found {} references in {}", references.size(), resourceType);

        for (Reference reference : references) {
            String originalRef = reference.getReference();
            logger.debug("🔍 Processing reference: {}", originalRef);

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
                        logger.debug("🔗 Resolved reference in {}: {} → {}", resourceType, originalRef, actualReference);
                    } else {
                        logger.warn("⚠️ Could not resolve UUID reference: {} (uuid: {})", originalRef, uuid);
                        logger.debug("⚠️ Available mappings: {}", uuidToIdMapping.keySet());
                    }
                } else {
                    logger.warn("⚠️ Could not extract UUID from reference: {}", originalRef);
                }
            } else {
                logger.debug("📝 Non-UUID reference (skipping): {}", originalRef);
            }
        }
    }

    /**
     * Insert a single resource into Couchbase using transaction context
     */
    private void insertResourceInTransaction(com.couchbase.client.java.transactions.TransactionAttemptContext ctx,
                                           Cluster cluster, String bucketName, String resourceType, String documentKey, Resource resource) {
        try {
            // Apply meta information
            com.couchbase.common.fhir.FhirMetaHelper.applyMeta(resource, new Date(), "1", null, "user:anonymous");
            
            // Convert to JSON
            String resourceJson = jsonParser.encodeResourceToString(resource);
            
            // Insert using transaction context
            logger.info("🔧 Inserting {} into transaction: {}", resourceType, documentKey);
            // Get collection reference from cluster (passed separately)
            com.couchbase.client.java.Collection collection = cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(resourceType);
            ctx.insert(collection, 
                      documentKey, 
                      com.couchbase.client.java.json.JsonObject.fromJson(resourceJson));
            
            logger.info("✅ Inserted {}/{} in transaction", resourceType, resource.getIdElement().getIdPart());
            
        } catch (Exception e) {
            logger.error("❌ Failed to insert {} in transaction: {}", resourceType, e.getMessage());
            throw new RuntimeException("Transaction insert failed for " + resourceType + ": " + e.getMessage(), e);
        }
    }

    /**
     * Insert a single resource into Couchbase
     */
    private void insertResourceIntoCouchbase(Cluster cluster, String bucketName, String resourceType,
                                             String documentKey, Resource resource) {
        // Ensure proper meta information using FhirMetaHelper
        String versionId = "1";
        Date lastUpdated = new Date();
        java.util.List<String> profiles = null;
        if (resource.getMeta() != null && resource.getMeta().hasProfile()) {
            profiles = new java.util.ArrayList<>();
            for (org.hl7.fhir.r4.model.CanonicalType ct : resource.getMeta().getProfile()) {
                profiles.add(ct.getValue());
            }
        }
        // Use auditService to get user info if available, else fallback
        String createdBy = "user:anonymous";
        try {
            com.couchbase.fhir.resources.service.FhirAuditService auditService = this.auditService;
            if (auditService != null) {
                com.couchbase.fhir.resources.service.UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
                if (auditInfo != null && auditInfo.getUserId() != null) {
                    createdBy = "user:" + auditInfo.getUserId();
                }
            }
        } catch (Exception e) {}
        com.couchbase.common.fhir.FhirMetaHelper.applyMeta(
                resource,
                lastUpdated,
                versionId,
                profiles,
                createdBy
        );

        // Convert to JSON and then to Map for Couchbase
        String resourceJson = jsonParser.encodeResourceToString(resource);
        Map<String, Object> resourceMap = JsonObject.fromJson(resourceJson).toMap();

        // UPSERT into appropriate collection
        String sql = String.format(
                "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey,
                JsonObject.from(resourceMap).toString()
        );

        logger.info("🔧 Executing SQL: {}", sql);
        cluster.query(sql);
        logger.info("✅ Upserted {}/{} into collection", resourceType, resource.getIdElement().getIdPart());
    }

    /**
     * Create processed entries from Bundle without re-processing (for transaction responses)
     */
    private List<ProcessedEntry> createProcessedEntriesFromBundle(Bundle bundle) {
        List<ProcessedEntry> processedEntries = new ArrayList<>();
        
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            String resourceId = resource.getIdElement().getIdPart();
            String documentKey = resourceType + "/" + resourceId;
            
            // Create response entry
            Bundle.BundleEntryComponent responseEntry = createResponseEntry(resource, resourceType);
            processedEntries.add(ProcessedEntry.success(resourceType, resourceId, documentKey, responseEntry));
        }
        
        logger.debug("✅ Created {} processed entries from Bundle (no re-processing)", processedEntries.size());
        return processedEntries;
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
     * Validate Bundle structure using bucket-specific validation configuration
     */
    private ValidationResult validateBundle(Bundle bundle, com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        FhirValidator validator;
        boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
        
        if (useLenientValidation || !bucketConfig.isEnforceUSCore()) {
            validator = basicFhirValidator;
            logger.info("Using basic FHIR R4 validator (lenient: {}, enforceUSCore: {})", 
                       useLenientValidation, bucketConfig.isEnforceUSCore());
        } else {
            validator = fhirValidator;
            logger.info("Using strict US Core 6.1.0 validator");
        }

        ValidationResult result = validator.validateWithResult(bundle);

        // Filter out INFORMATION level messages
        List<SingleValidationMessage> filteredMessages = result
                .getMessages()
                .stream()
                .filter(msg -> msg.getSeverity() != ResultSeverityEnum.INFORMATION)
                .collect(Collectors.toList());

        return new ValidationResult(result.getContext(), filteredMessages);
    }

    /**
     * Create comprehensive response
     */
    /**
     * Create proper FHIR transaction-response Bundle
     */
    private Bundle createTransactionResponseBundle(List<ProcessedEntry> processedEntries,
                                                   Bundle.BundleType originalType) {
        Bundle responseBundle = new Bundle();

        // Set response type based on original bundle type
        if (originalType == Bundle.BundleType.TRANSACTION) {
            responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
        } else if (originalType == Bundle.BundleType.BATCH) {
            responseBundle.setType(Bundle.BundleType.BATCHRESPONSE);
        } else {
            responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE); // Default
        }

        responseBundle.setId(UUID.randomUUID().toString());
        responseBundle.setTimestamp(new Date());

        // Add meta information
        Meta bundleMeta = new Meta();
        bundleMeta.setLastUpdated(new Date());
        responseBundle.setMeta(bundleMeta);

        logger.debug("📦 Creating {} response with {} entries",
                responseBundle.getType().name(), processedEntries.size());

        // Add all response entries
        for (ProcessedEntry entry : processedEntries) {
            if (entry.isSuccess()) {
                responseBundle.addEntry(entry.getResponseEntry());
                logger.debug("✅ Added successful entry: {}/{}", entry.getResourceType(), entry.getResourceId());
            } else {
                // Create error entry
                Bundle.BundleEntryComponent errorEntry = createErrorEntry(entry.getErrorMessage());
                responseBundle.addEntry(errorEntry);
                logger.warn("❌ Added error entry: {}", entry.getErrorMessage());
            }
        }

        logger.info("📋 Created {} Bundle response with {} entries",
                responseBundle.getType().name(), responseBundle.getEntry().size());

        return responseBundle;
    }

    /**
     * Create error entry for Bundle response
     */
    private Bundle.BundleEntryComponent createErrorEntry(String errorMessage) {
        Bundle.BundleEntryComponent errorEntry = new Bundle.BundleEntryComponent();
        Bundle.BundleEntryResponseComponent errorResponse = new Bundle.BundleEntryResponseComponent();
        errorResponse.setStatus("400 Bad Request");
        errorResponse.setOutcome(createOperationOutcome(errorMessage));
        errorEntry.setResponse(errorResponse);
        return errorEntry;
    }
    /**
     * Create OperationOutcome for error responses
     */
    private OperationOutcome createOperationOutcome(String errorMessage) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = new OperationOutcome.OperationOutcomeIssueComponent();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.PROCESSING);
        issue.setDiagnostics(errorMessage);
        outcome.addIssue(issue);
        return outcome;
    }

    // Removed unused getCurrentFhirTimestamp method



    /**
     * Create validation failure response
     */


    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }

    private boolean isCouchbaseError(Exception e) {
        String message = e.getMessage();
        return message != null && (
            message.contains("COMMIT statement is not supported") ||
            message.contains("ROLLBACK statement is not supported") ||
            message.contains("Unknown query error") ||
            message.contains("Bundle transaction failed")
        );
    }

    private String extractCleanErrorMessage(Exception e) {
        String message = e.getMessage();
        
        if (message == null) {
            return "Bundle processing failed due to unknown error";
        }
        
        // Handle Couchbase transaction errors
        if (message.contains("COMMIT statement is not supported")) {
            return "Database transaction error: Transaction commit failed. Please check Couchbase transaction configuration.";
        }
        
        if (message.contains("ROLLBACK statement is not supported")) {
            return "Database transaction error: Transaction rollback failed. Please check Couchbase transaction configuration.";
        }
        
        // Handle bundle transaction failures with JSON error details
        if (message.contains("Bundle transaction failed: Unknown query error")) {
            try {
                // Extract just the error message from the JSON blob
                if (message.contains("\"message\":\"")) {
                    int start = message.indexOf("\"message\":\"") + 11;
                    int end = message.indexOf("\"", start);
                    if (start > 10 && end > start) {
                        String errorMsg = message.substring(start, end);
                        return "Database error: " + errorMsg;
                    }
                }
            } catch (Exception ex) {
                // Fall back if JSON parsing fails
            }
            return "Bundle transaction failed due to database error";
        }
        
        // For other bundle processing errors, try to extract the root cause
        if (message.contains("Bundle processing failed:")) {
            return message; // Already clean
        }
        
        // Truncate very long messages
        if (message.length() > 200) {
            return message.substring(0, 200) + "... (error message truncated)";
        }
        
        return message;
    }
}