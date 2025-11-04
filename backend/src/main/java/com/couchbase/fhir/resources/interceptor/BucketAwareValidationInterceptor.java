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
import com.couchbase.fhir.resources.util.FhirErrorResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Interceptor to handle bucket validation and strict validation by parsing request body before HAPI's default parsing
 */
@Component
@Interceptor
public class BucketAwareValidationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(BucketAwareValidationInterceptor.class);
    private static final String UD_REQ_START_NS = "_req_start_ns";
    private static final String UD_REQ_BODY = "_req_body";
    private static final String UD_PERF_BAG = "_perf_bag";

    @Autowired
    private FhirBucketConfigService configService;

    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FhirErrorResponseUtil errorResponseUtil;

    // Early validation - before request is processed
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
    public boolean validateBucketEnabled(RequestDetails theRequestDetails, HttpServletRequest theRequest, HttpServletResponse theResponse) throws IOException {
        // Extract bucket name directly from URL path since TenantContextHolder isn't populated yet
        String bucketName = extractBucketFromPath(theRequest.getRequestURI());
        
        try {
            logger.debug("🔍 Validating bucket is FHIR-enabled: {}", bucketName);
            
            // This will throw FhirBucketValidationException if bucket is not FHIR-enabled
            FhirBucketConfigService.FhirBucketConfig config = configService.getFhirBucketConfig(bucketName);
            
            logger.debug("✅ Bucket {} is FHIR-enabled with mode: {}", bucketName, config.getValidationMode());
            return true; // Continue processing
            
        } catch (Exception e) {
            logger.error("❌ Bucket validation failed for {}: {}", bucketName, e.getMessage());
            
            // Use utility to write standardized FHIR error response
            errorResponseUtil.writeBucketNotEnabledResponse(theResponse, bucketName, e.getMessage());
            
            return false; // Stop processing - don't continue to other hooks
        }
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void validateStrictBuckets(RequestDetails theRequestDetails) {
        // Cache request start time and body for later hooks/validation
        theRequestDetails.getUserData().put(UD_REQ_START_NS, System.nanoTime());
        
        // Create performance tracking bag for this request
        RequestPerfBag perfBag = new RequestPerfBag();
        theRequestDetails.getUserData().put(UD_PERF_BAG, perfBag);
        
        // Set basic request info
        String operationName = theRequestDetails.getRestOperationType() != null ? 
            theRequestDetails.getRestOperationType().name() : "UNKNOWN";
        String resource = theRequestDetails.getResourceName();
        String bucket = TenantContextHolder.getTenantId();
        perfBag.setRequestInfo(operationName, resource, bucket);
        
        // Log complete REST request details
        logIncomingRequest(theRequestDetails, perfBag);
        byte[] cachedBody = null;
        RestOperationTypeEnum operation = theRequestDetails.getRestOperationType();
        if (operation == RestOperationTypeEnum.CREATE || operation == RestOperationTypeEnum.UPDATE || operation == RestOperationTypeEnum.PATCH || operation == RestOperationTypeEnum.TRANSACTION) {
            cachedBody = theRequestDetails.loadRequestContents();
            theRequestDetails.getUserData().put(UD_REQ_BODY, cachedBody);
        }
        String bucketName = TenantContextHolder.getTenantId();

        // Get bucket config (this should work now since we validated bucket earlier)
        try {
            logger.debug("🔍 Getting bucket config for strict validation: {}", bucketName);
            FhirBucketConfigService.FhirBucketConfig config = configService.getFhirBucketConfig(bucketName);
            logger.debug("🔍 Bucket config - mode: {}, profile: {}", config.getValidationMode(), config.getValidationProfile());
            
            if ("strict".equals(config.getValidationMode())) {
                logger.debug("🔍 APPLYING STRICT VALIDATION for bucket: {}", bucketName);
                byte[] requestBody = (byte[]) theRequestDetails.getUserData().get(UD_REQ_BODY);
                logger.debug("🔍 Request body size: {} bytes", requestBody != null ? requestBody.length : 0);
                
                if (requestBody != null && requestBody.length > 0) {
                    String jsonContent = new String(requestBody, java.nio.charset.StandardCharsets.UTF_8);
                    IParser strictParser = fhirContext.newJsonParser();
                    strictParser.setParserErrorHandler(new StrictErrorHandler());
                    
                    try {
                        strictParser.parseResource(jsonContent);
                        logger.debug("🔍 ✅ Strict validation PASSED for bucket: {}", bucketName);
                    } catch (Exception e) {
                        logger.error("🔍 ❌ Strict validation FAILED for bucket {}: {}", bucketName, e.getMessage());
                        logger.error("🔍 Exception type: {}", e.getClass().getSimpleName());
                        throw new ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException(
                            "Invalid FHIR resource for strict bucket '" + bucketName + "': " + e.getMessage()
                        );
                    }
                }
            } else if ("disabled".equals(config.getValidationMode())) {
                logger.debug("🔍 VALIDATION DISABLED for bucket: {}", bucketName);
            } else {
                String validationType = "us-core".equals(config.getValidationProfile()) ? "US Core 6.1.0" : "basic FHIR R4";
                logger.debug("🔍 Using {} {} validation for bucket: {}", config.getValidationMode().toUpperCase(), validationType, bucketName);
            }
            
        } catch (Exception e) {
            if (e instanceof ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException) {
                logger.error("🔍 Re-throwing UnprocessableEntityException: {}", e.getMessage());
                throw e;
            }
            // This shouldn't happen now since we validated the bucket earlier
            logger.error("🔍 Unexpected error in strict validation for bucket {}: {}", bucketName, e.getMessage());
            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException(
                "Unexpected error during validation for bucket '" + bucketName + "': " + e.getMessage()
            );
        }
    }

    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    public void after(RequestDetails rd) {
        RequestPerfBag perfBag = (RequestPerfBag) rd.getUserData().get(UD_PERF_BAG);
        if (perfBag != null) {
            perfBag.setStatus("success");
            logger.debug("✅ op={} | {}", 
                perfBag.getOperation(),
                perfBag.getSummary());
        } else {
            // Fallback to old method if PerfBag is missing
            long start = (long) rd.getUserData().getOrDefault(UD_REQ_START_NS, System.nanoTime());
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            var op = rd.getRestOperationType();
            var rt = rd.getResourceName();
            logger.info("✅ op={}, resource={}, took={}ms, params={}",
                (op != null ? op.name() : "UNKNOWN"),
                (rt != null ? rt : "UNKNOWN"),
                tookMs,
                rd.getParameters());
        }
    }

    @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
    public boolean onException(RequestDetails rd, Throwable e) {
        // Check for null exception to prevent NPE
        // This can happen when HAPI properly catches and converts exceptions to OperationOutcome
        if (e == null) {
            logger.debug("🔍 onException called with null (exception already handled by HAPI)");
            return true;
        }
        
        RequestPerfBag perfBag = (RequestPerfBag) rd.getUserData().get(UD_PERF_BAG);
        if (perfBag != null) {
            perfBag.setStatus("error");
            logger.error("❌ op={}, error={}: {} | {}",
                perfBag.getOperation(),
                e.getClass().getSimpleName(),
                e.getMessage(),
                perfBag.getSummary());
        } else {
            // Fallback to old method if PerfBag is missing
            long start = (long) rd.getUserData().getOrDefault(UD_REQ_START_NS, System.nanoTime());
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            var op = rd.getRestOperationType();
            var rt = rd.getResourceName();
            logger.error("❌ op={}, resource={}, took={}ms, error={}: {}",
                (op != null ? op.name() : "UNKNOWN"),
                (rt != null ? rt : "UNKNOWN"),
                tookMs,
                e.getClass().getSimpleName(),
                e.getMessage());
        }
        return true; // let HAPI continue normal exception handling
    }
    
    /**
     * Logs incoming REST request method and URL with request ID
     */
    private void logIncomingRequest(RequestDetails theRequestDetails, RequestPerfBag perfBag) {
        try {
            String method = theRequestDetails.getRequestType() != null ? theRequestDetails.getRequestType().name() : "UNKNOWN";
            String completeUrl = theRequestDetails.getCompleteUrl();
            logger.info("🌐 INCOMING REQUEST: {} {} | reqId={}", method, completeUrl, perfBag.getRequestId());
        } catch (Exception e) {
            logger.warn("🌐 Failed to log incoming request details: {}", e.getMessage());
        }
    }
    
    /**
     * Extracts bucket name from FHIR URL path
     * Expected format: /fhir/{bucketName}/ResourceType/...
     */
    private String extractBucketFromPath(String requestURI) {
        if (requestURI == null || requestURI.isEmpty()) {
            return null;
        }
        
        try {
            // Split path and find bucket name after /fhir/
            String[] pathParts = requestURI.split("/");
            
            // Look for pattern: /fhir/{bucketName}/...
            for (int i = 0; i < pathParts.length - 1; i++) {
                if ("fhir".equals(pathParts[i]) && i + 1 < pathParts.length) {
                    String bucketName = pathParts[i + 1];
                    logger.debug("🔍 Extracted bucket name '{}' from path: {}", bucketName, requestURI);
                    return bucketName;
                }
            }
            
            logger.warn("🔍 Could not extract bucket name from path: {}", requestURI);
            return null;
            
        } catch (Exception e) {
            logger.error("🔍 Error extracting bucket name from path {}: {}", requestURI, e.getMessage());
            return null;
        }
    }
    
}