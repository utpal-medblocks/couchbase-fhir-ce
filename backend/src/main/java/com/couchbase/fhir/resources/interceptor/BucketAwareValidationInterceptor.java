package com.couchbase.fhir.resources.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Interceptor;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Interceptor to handle strict validation by parsing request body before HAPI's default parsing
 */
@Component
@Interceptor
public class BucketAwareValidationInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(BucketAwareValidationInterceptor.class);
    
    @Autowired
    private FhirBucketConfigService configService;
    
    @Autowired
    private FhirContext fhirContext;
    
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void validateStrictBuckets(RequestDetails theRequestDetails) {
        logger.info("🔍 BucketAwareValidationInterceptor: Processing request - Operation: {}, URL: {}", 
            theRequestDetails.getRestOperationType(), theRequestDetails.getRequestPath());
            
        // Validate CREATE operations and TRANSACTION operations (Bundle processing)
        RestOperationTypeEnum operation = theRequestDetails.getRestOperationType();
        if (operation != RestOperationTypeEnum.CREATE && operation != RestOperationTypeEnum.TRANSACTION) {
            logger.info("🔍 Skipping operation: {} (only CREATE and TRANSACTION are validated)", operation);
            return;
        }
        
        String bucketName = TenantContextHolder.getTenantId();
        logger.info("🔍 Bucket name from TenantContextHolder: {}", bucketName);
        
        if (bucketName == null) {
            logger.warn("🔍 No bucket name found, skipping validation");
            return;
        }
        
        try {
            logger.info("🔍 Getting bucket config for: {}", bucketName);
            FhirBucketConfigService.FhirBucketConfig config = configService.getFhirBucketConfig(bucketName);
            
            // Log complete validation configuration
            logger.info("🔍 Bucket config - mode: {}, enforceUSCore: {}, allowUnknown: {}, terminology: {}", 
                config.getValidationMode(), config.isEnforceUSCore(), 
                config.isAllowUnknownElements(), config.isTerminologyChecks());
            
            // Apply strict parsing validation based on mode and allowUnknownElements
            if ("strict".equals(config.getValidationMode()) && !config.isAllowUnknownElements()) {
                logger.info("🔍 APPLYING STRICT VALIDATION for bucket: {}", bucketName);
                
                // Get the request body before HAPI parses it
                byte[] requestBody = theRequestDetails.loadRequestContents();
                logger.info("🔍 Request body size: {} bytes", requestBody != null ? requestBody.length : 0);
                
                if (requestBody != null && requestBody.length > 0) {
                    String jsonContent = new String(requestBody, StandardCharsets.UTF_8);
                    logger.info("🔍 Request JSON content: {}", jsonContent);
                    
                    // Parse with strict error handler to catch unknown fields
                    IParser strictParser = fhirContext.newJsonParser();
                    strictParser.setParserErrorHandler(new StrictErrorHandler());
                    
                    try {
                        // This will throw an exception if there are unknown fields
                        strictParser.parseResource(jsonContent);
                        logger.info("🔍 ✅ Strict validation PASSED for bucket: {}", bucketName);
                    } catch (Exception e) {
                        logger.error("🔍 ❌ Strict validation FAILED for bucket {}: {}", bucketName, e.getMessage());
                        logger.error("🔍 Exception type: {}", e.getClass().getSimpleName());
                        throw new ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException(
                            "Invalid FHIR resource for strict bucket '" + bucketName + "': " + e.getMessage()
                        );
                    }
                }
            } else if ("disabled".equals(config.getValidationMode())) {
                logger.info("🔍 VALIDATION DISABLED for bucket: {}", bucketName);
            } else {
                String validationType = config.isEnforceUSCore() ? "US Core" : "basic FHIR R4";
                logger.info("🔍 Using {} {} validation for bucket: {} (allowUnknown: {})", 
                    config.getValidationMode().toUpperCase(), validationType, bucketName, config.isAllowUnknownElements());
            }
        } catch (Exception e) {
            if (e instanceof ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException) {
                logger.error("🔍 Re-throwing UnprocessableEntityException: {}", e.getMessage());
                throw e;
            }
            logger.error("🔍 Error in bucket validation interceptor for bucket {}: {}", bucketName, e.getMessage(), e);
        }
    }
}