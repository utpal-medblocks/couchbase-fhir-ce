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
import com.couchbase.client.java.query.QueryResult;
import java.util.Map;
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
    
    @Autowired
    private PostService postService;
    
    @Autowired
    private PutService putService;
    
    @Autowired
    private DeleteService deleteService;

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

        logger.debug("✅ Bundle Processing Service optimized for high-performance transactions");
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
            // Extract validation settings from simplified bucket config
            boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
            boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
            boolean enforceUSCore = "us-core".equals(bucketConfig.getValidationProfile());
            
            // Build validation description
            String validationType;
            if (skipValidation) {
                validationType = "NONE (disabled)";
            } else {
                StringBuilder desc = new StringBuilder();
                desc.append(useLenientValidation ? "lenient" : "strict");
                desc.append(" (").append(enforceUSCore ? "US Core 6.1.0" : "basic FHIR R4").append(")");
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
                // Uncomment for stack trace during deep debugging:
                // logger.debug("❌ Full stack trace:", e);
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
                // Uncomment for stack trace during deep debugging:
                // logger.debug("❌ Couchbase error details:", e);
            } else {
                // Uncomment for stack trace during deep debugging:
                // logger.debug("❌ Full stack trace:", e);
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
     * Now orchestrates individual POST, PUT, DELETE services
     */
    private void processEntriesInTransactionContext(com.couchbase.client.java.transactions.TransactionAttemptContext ctx, 
                                                   Bundle bundle, Cluster cluster, String bucketName, 
                                                   com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        logger.info("🔄 Processing {} Bundle entries within transaction using service orchestration", bundle.getEntry().size());
        
        // Step 1: Build UUID mapping for all entries first (for POST operations)
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);
        
        // Step 2: Create transaction context for services
        TransactionContext transactionContext = new TransactionContextImpl(cluster, bucketName, ctx);
        
        // Step 3: Process each entry using appropriate service
        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            Bundle.HTTPVerb method = entry.getRequest() != null ? entry.getRequest().getMethod() : Bundle.HTTPVerb.POST;
            
            logger.info("🔄 Processing entry {}/{}: {} {} in transaction", i+1, bundle.getEntry().size(), method, resourceType);
            
            try {
                // Step 3a: Resolve UUID references in this resource (for POST operations)
                if (method == Bundle.HTTPVerb.POST) {
                    resolveUuidReferencesInResource(resource, uuidToIdMapping);
                }
                
                // Step 3b: Route to appropriate service based on HTTP method
                switch (method) {
                    case POST:
                        postService.createResourceInTransaction(resource, ctx, cluster, bucketName);
                        logger.info("✅ POST {}: Created with server-generated ID {}", resourceType, resource.getId());
                        break;
                        
                    case PUT:
                        TransactionContext putContext = new TransactionContextImpl(cluster, bucketName, ctx);
                        putService.updateOrCreateResource(resource, putContext);
                        logger.info("✅ PUT {}: Updated/created with ID {}", resourceType, resource.getId());
                        break;
                        
                    case DELETE:
                        String resourceId = extractResourceIdFromUrl(entry.getRequest().getUrl());
                        if (resourceId != null) {
                            deleteService.deleteResource(resourceType, resourceId, transactionContext);
                            logger.info("✅ DELETE {}: Soft deleted ID {}", resourceType, resourceId);
                        } else {
                            throw new RuntimeException("DELETE operation requires resource ID in request URL");
                        }
                        break;
                        
                    default:
                        throw new RuntimeException("Unsupported HTTP method in Bundle: " + method);
                }
                
            } catch (Exception e) {
                logger.error("❌ Failed to process {} {} in transaction: {}", method, resourceType, e.getMessage());
                throw new RuntimeException("Transaction failed processing " + method + " " + resourceType + ": " + e.getMessage(), e);
            }
        }
        
        logger.info("✅ All Bundle entries processed successfully within transaction");
    }
    
    /**
     * Internal method to process Bundle entries with a given cluster connection
     * Used by BATCH processing (non-transaction) - now orchestrates individual services
     */
    private List<ProcessedEntry> processEntriesSequentiallyInternal(Bundle bundle, Cluster cluster, String bucketName, 
                                                                   com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        boolean skipValidation = "disabled".equals(bucketConfig.getValidationMode());
        
        // Step 1: Build UUID mapping for POST entries
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);

        // Step 2: Create standalone transaction context for services
        TransactionContext standaloneContext = new TransactionContextImpl(cluster, bucketName);
        
        // Step 3: Process each entry in order using service orchestration
        List<ProcessedEntry> processedEntries = new ArrayList<>();
        
        logger.info("🔄 Starting to process {} Bundle entries sequentially (BATCH mode)", bundle.getEntry().size());

        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();
            Bundle.HTTPVerb method = entry.getRequest() != null ? entry.getRequest().getMethod() : Bundle.HTTPVerb.POST;
            
            logger.info("🔄 Processing entry {}/{}: {} {} resource", i+1, bundle.getEntry().size(), method, resourceType);

            try {
                Resource processedResource = null;
                String responseStatus = "201 Created";
                
                // Step 3a: Route to appropriate service based on HTTP method
                switch (method) {
                    case POST:
                        // Resolve UUID references for POST operations
                        resolveUuidReferencesInResource(resource, uuidToIdMapping);
                        
                        // Validate if enabled
                        if (!skipValidation) {
                            validateResource(resource, bucketConfig);
                        }
                        
                        processedResource = postService.createResource(resource, cluster, bucketName);
                        responseStatus = "201 Created";
                        logger.info("✅ POST {}: Created with server-generated ID {}", resourceType, processedResource.getId());
                        break;
                        
                    case PUT:
                        // Validate if enabled
                        if (!skipValidation) {
                            validateResource(resource, bucketConfig);
                        }
                        
                        boolean wasCreated = !resourceExists(cluster, bucketName, resourceType, resource.getId());
                        processedResource = putService.updateOrCreateResource(resource, standaloneContext);
                        responseStatus = wasCreated ? "201 Created" : "200 OK";
                        logger.info("✅ PUT {}: {} with ID {}", resourceType, wasCreated ? "Created" : "Updated", processedResource.getId());
                        break;
                        
                    case DELETE:
                        String resourceId = extractResourceIdFromUrl(entry.getRequest().getUrl());
                        if (resourceId != null) {
                            deleteService.deleteResource(resourceType, resourceId, standaloneContext);
                            responseStatus = "204 No Content";
                            logger.info("✅ DELETE {}: Soft deleted ID {}", resourceType, resourceId);
                            // For DELETE, we don't have a resource to return
                            processedResource = null;
                        } else {
                            throw new RuntimeException("DELETE operation requires resource ID in request URL");
                        }
                        break;
                        
                    default:
                        throw new RuntimeException("Unsupported HTTP method in Bundle: " + method);
                }
                
                // Step 3b: Create response entry
                Bundle.BundleEntryComponent responseEntry = createResponseEntryForMethod(processedResource, resourceType, method, responseStatus);
                String documentKey = processedResource != null ? resourceType + "/" + processedResource.getId() : resourceType + "/" + "deleted";
                String resourceId = processedResource != null ? processedResource.getId() : "deleted";
                
                processedEntries.add(ProcessedEntry.success(resourceType, resourceId, documentKey, responseEntry));
                logger.debug("✅ Successfully processed {} {}", method, resourceType);

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.error("❌ Failed to process {} {} entry: {}", method, resourceType, errorMessage, e);

                processedEntries.add(ProcessedEntry.failed("Failed to process " + method + " " + resourceType + ": " + errorMessage));
            }
        }

        return processedEntries;
    }

    /**
     * Build UUID mapping for all entries in the Bundle
     * For POST operations: Always generate new IDs (ignore client-supplied IDs)
     * For PUT operations: Use client-supplied IDs (not implemented yet)
     */
    private Map<String, String> buildUuidMapping(Bundle bundle) {
        Map<String, String> uuidToIdMapping = new HashMap<>();

        logger.debug("🔄 Building UUID mapping for Bundle with {} entries", bundle.getEntry().size());

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();

            logger.debug("📝 Processing entry - ResourceType: {}, FullUrl: {}, Initial ID: {}",
                    resourceType, entry.getFullUrl(), resource.getId());

            // ✅ FHIR Compliance: For POST operations, ALWAYS generate new IDs
            // The server ignores any client-supplied IDs and generates its own
            String actualResourceId = generateResourceId(resourceType);
            logger.debug("🆔 Generated new server ID for {}: {} (ignoring any client-supplied ID)", resourceType, actualResourceId);

            // Map urn:uuid references to the new server-generated ID
            if (entry.getFullUrl() != null && entry.getFullUrl().startsWith("urn:uuid:")) {
                String uuidFullUrl = entry.getFullUrl(); // "urn:uuid:550e8400-e29b-41d4-a716-446655440000"
                String mappedReference = resourceType + "/" + actualResourceId; // "Patient/abc123-def456-..."
                uuidToIdMapping.put(uuidFullUrl, mappedReference);
                logger.debug("🔗 UUID mapping: {} → {}", uuidFullUrl, mappedReference);
            }

            // ✅ Always set the server-generated ID on the resource (overwrite any client ID)
            resource.setId(actualResourceId);
        }

        logger.debug("📊 Final UUID mapping: {}", uuidToIdMapping);
        return uuidToIdMapping;
    }

    // Removed extractIdFromUuid and isValidResourceId methods - no longer needed
    // Server always generates its own IDs for POST operations

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

    // Removed insertResourceInTransaction - now handled by PostService

    // Removed insertResourceIntoCouchbase - now handled by individual services

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
        return createResponseEntryForMethod(resource, resourceType, Bundle.HTTPVerb.POST, "201 Created");
    }
    
    /**
     * Create a response entry for different HTTP methods
     */
    private Bundle.BundleEntryComponent createResponseEntryForMethod(Resource resource, String resourceType, Bundle.HTTPVerb method, String status) {
        Bundle.BundleEntryComponent responseEntry = new Bundle.BundleEntryComponent();

        // Set the resource in response (except for DELETE)
        if (resource != null && method != Bundle.HTTPVerb.DELETE) {
            responseEntry.setResource(resource);
        }

        // Set response details
        Bundle.BundleEntryResponseComponent response = new Bundle.BundleEntryResponseComponent();
        response.setStatus(status);
        
        if (resource != null) {
            response.setLocation(resourceType + "/" + resource.getIdElement().getIdPart());
        }
        
        responseEntry.setResponse(response);
        return responseEntry;
    }





    /**
     * Validate Bundle structure using bucket-specific validation configuration
     */
    private ValidationResult validateBundle(Bundle bundle, com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        FhirValidator validator;
        boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
        
        boolean enforceUSCore = "us-core".equals(bucketConfig.getValidationProfile());
        
        if (useLenientValidation || !enforceUSCore) {
            validator = basicFhirValidator;
            logger.info("Using basic FHIR R4 validator (mode: {}, profile: {})", 
                       bucketConfig.getValidationMode(), bucketConfig.getValidationProfile());
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

    /**
     * Extract resource ID from Bundle request URL (for DELETE operations)
     */
    private String extractResourceIdFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        
        // Handle URLs like "Patient/1234" or "/Patient/1234"
        String[] parts = url.split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 1]; // Get the last part (ID)
        }
        
        return null;
    }
    
    /**
     * Check if a resource exists in the live collection
     */
    private boolean resourceExists(Cluster cluster, String bucketName, String resourceType, String resourceId) {
        try {
            String documentKey = resourceType + "/" + resourceId;
            String sql = String.format(
                "SELECT COUNT(*) AS count FROM `%s`.`%s`.`%s` USE KEYS '%s'",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey
            );
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAsObject();
            
            if (!rows.isEmpty()) {
                int count = rows.get(0).getInt("count");
                return count > 0;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.debug("Failed to check if resource exists {}/{}: {}", resourceType, resourceId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate a single resource using bucket configuration
     */
    private void validateResource(Resource resource, com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig bucketConfig) {
        // Choose validator based on simplified bucket config
        FhirValidator validator;
        boolean useLenientValidation = "lenient".equals(bucketConfig.getValidationMode());
        boolean enforceUSCore = "us-core".equals(bucketConfig.getValidationProfile());
        
        if (useLenientValidation || !enforceUSCore) {
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
            String resourceType = resource.getResourceType().name();
            logger.warn("⚠️ Validation failed for {} with {} significant issues", resourceType, validation.getMessages().size());
            // Continue processing even if validation fails (configurable behavior)
        }
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