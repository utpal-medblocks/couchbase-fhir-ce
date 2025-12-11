package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.rest.annotation.Transaction;
import ca.uhn.fhir.rest.annotation.TransactionParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HAPI FHIR provider for Bundle transaction and batch operations.
 * This provider handles Bundle transactions using our sophisticated FhirBundleProcessingService.
 */
@Component
public class BundleTransactionProvider implements IResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(BundleTransactionProvider.class);

    @Autowired
    private FhirBundleProcessingService bundleProcessingService;
    
    @Autowired
    private FhirBucketConfigService bucketConfigService;
    
    // FhirContext is injected into the parser, no need for direct reference
    
    @Autowired 
    private IParser jsonParser; // Reuse singleton parser
    
    @Autowired
    private FhirContext fhirContext;

    /**
     * Tell HAPI that this provider handles Bundle resources
     */
    @Override
    public Class<Bundle> getResourceType() {
        return Bundle.class;
    }

    /**
     * Handle Bundle transactions and batches
     * This method is called by HAPI FHIR when a Bundle is POSTed to the server
     */
    @Transaction
    public Bundle transaction(@TransactionParam Bundle bundle, ca.uhn.fhir.rest.api.server.RequestDetails requestDetails) {
        // Try to get the raw JSON from the interceptor cache first
        byte[] cachedBody = (byte[]) requestDetails.getUserData().get("_req_body");
        String bundleJson = null;
        
        if (cachedBody != null && cachedBody.length > 0) {
            bundleJson = new String(cachedBody, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("🔧 Using raw JSON from interceptor cache to avoid HAPI reference preprocessing");
        } else {
            // FAIL FAST - don't silently degrade to broken reference behavior
            throw new InvalidRequestException("Bundle processing requires raw request body - interceptor may not be configured properly");
        }
        
        // Parse the raw JSON to get Bundle type for validation
        // Create a special parser that doesn't process references
        Bundle rawBundle = null;
        try {
            IParser noReferenceParser = fhirContext.newJsonParser();
            noReferenceParser.setOverrideResourceIdWithBundleEntryFullUrl(false);
            noReferenceParser.setStripVersionsFromReferences(false);
            rawBundle = noReferenceParser.parseResource(Bundle.class, bundleJson);
        } catch (Exception e) {
            throw new InvalidRequestException("Failed to parse Bundle JSON: " + e.getMessage());
        }
        
        // Validate Bundle type - must be transaction or batch
        if (rawBundle.getType() != Bundle.BundleType.TRANSACTION && 
            rawBundle.getType() != Bundle.BundleType.BATCH) {
            throw new InvalidRequestException("Bundle type must be 'transaction' or 'batch', but was: " + 
                                            (rawBundle.getType() != null ? rawBundle.getType().toCode() : "null"));
        }
        
        try {
            String bucketName = TenantContextHolder.getTenantId();
            String connectionName = "default"; // Could be made configurable
            
            logger.debug("🚀 Processing Bundle {} for tenant: {}", 
                       rawBundle.getType().toCode(), bucketName);
            
            // Get complete bucket-specific validation configuration
            FhirBucketConfig bucketConfig = bucketConfigService.getFhirBucketConfig(bucketName);
            
            // Build validation description for logging
            String validationDescription = buildValidationDescription(bucketConfig);
            
            logger.debug("🔍 Bucket validation config - mode: {}, profile: {}", 
                       bucketConfig.getValidationMode(), bucketConfig.getValidationProfile());
            logger.debug("🔍 Using validation: {}", validationDescription);
            
            // Process using complete bucket configuration
            Bundle responseBundle = bundleProcessingService.processBundleTransaction(
                bundleJson, 
                connectionName, 
                bucketName,
                bucketConfig  // Pass complete config object
            );
            
            logger.debug("✅ Bundle processing completed successfully for tenant: {}", bucketName);
            return responseBundle;
            
        } catch (Exception e) {
            // Use the clean error message from the service
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.isEmpty()) {
                errorMessage = "Bundle processing failed due to unknown error";
            }
            
            logger.error("❌ Bundle processing failed: {}", errorMessage);
            logger.debug("❌ Full error details:", e);
            
            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException(errorMessage);
        }
    }
    
    /**
     * Build a human-readable validation description from simplified bucket config
     */
    private String buildValidationDescription(FhirBucketConfig config) {
        String validationMode = config.getValidationMode();
        
        if ("disabled".equals(validationMode)) {
            return "NO VALIDATION";
        }
        
        StringBuilder desc = new StringBuilder();
        desc.append("lenient".equals(validationMode) ? "LENIENT" : "STRICT");
        desc.append(" (");
        desc.append("us-core".equals(config.getValidationProfile()) ? "US Core 6.1.0" : "basic FHIR R4");
        desc.append(")");
        
        return desc.toString();
    }
}
