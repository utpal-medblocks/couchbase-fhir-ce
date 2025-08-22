package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.rest.annotation.Transaction;
import ca.uhn.fhir.rest.annotation.TransactionParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBundleProcessingService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.FhirBucketConfigService.FhirBucketConfig;

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
    public Bundle transaction(@TransactionParam Bundle bundle) {
        // Validate Bundle type - must be transaction or batch
        if (bundle.getType() != Bundle.BundleType.TRANSACTION && 
            bundle.getType() != Bundle.BundleType.BATCH) {
            throw new InvalidRequestException("Bundle type must be 'transaction' or 'batch', but was: " + 
                                            (bundle.getType() != null ? bundle.getType().toCode() : "null"));
        }
        try {
            String bucketName = TenantContextHolder.getTenantId();
            String connectionName = "default"; // Could be made configurable
            
            logger.info("🚀 Processing Bundle {} for tenant: {}", 
                       bundle.getType().toCode(), bucketName);
            
            // Convert Bundle to JSON using singleton parser
            String bundleJson = jsonParser.encodeResourceToString(bundle);
            
            // Get complete bucket-specific validation configuration
            FhirBucketConfig bucketConfig = bucketConfigService.getFhirBucketConfig(bucketName);
            
            // Build validation description for logging
            String validationDescription = buildValidationDescription(bucketConfig);
            
            logger.info("🔍 Bucket validation config - mode: {}, profile: {}", 
                       bucketConfig.getValidationMode(), bucketConfig.getValidationProfile());
            logger.info("🔍 Using validation: {}", validationDescription);
            
            // Process using complete bucket configuration
            Bundle responseBundle = bundleProcessingService.processBundleTransaction(
                bundleJson, 
                connectionName, 
                bucketName,
                bucketConfig  // Pass complete config object
            );
            
            logger.info("✅ Bundle processing completed successfully for tenant: {}", bucketName);
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
