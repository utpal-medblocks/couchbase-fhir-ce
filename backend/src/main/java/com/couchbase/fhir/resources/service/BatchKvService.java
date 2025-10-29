package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.interceptor.DAOTimingContext;
import com.google.common.base.Stopwatch;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Efficient batch KV document retrieval service.
 * This service retrieves multiple documents by their keys using Couchbase KV operations.
 */
@Service
public class BatchKvService {
    
    private static final Logger logger = LoggerFactory.getLogger(BatchKvService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    
    @Autowired
    private com.couchbase.fhir.resources.gateway.CouchbaseGateway couchbaseGateway;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    @Autowired
    private FhirContext fhirContext;
    
    /**
     * Retrieve multiple documents by their keys and parse them into FHIR resources
     * 
     * @param documentKeys List of document keys (e.g., "Patient/123", "Observation/456")
     * @param resourceType FHIR resource type for parsing
     * @return List of parsed FHIR resources in the same order as documentKeys
     */
    public List<Resource> getDocuments(List<String> documentKeys, String resourceType) {
        if (documentKeys == null || documentKeys.isEmpty()) {
            return new ArrayList<>();
        }
        
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.info("🔑 Batch KV retrieval: {} documents for {}", documentKeys.size(), resourceType);
        
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        try {
            // Get cached collection (avoids lookup overhead + triggers lazy warmup)
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            Collection collection = couchbaseGateway.getCollection("default", bucketName, DEFAULT_SCOPE, targetCollection);
            
            // Execute async KV operations in parallel for maximum performance
            List<CompletableFuture<GetResult>> futures = new ArrayList<>();
            
            for (String documentKey : documentKeys) {
                CompletableFuture<GetResult> future = collection.async().get(documentKey,
                    GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
                futures.add(future);
            }
            
            // Wait for all KV operations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS); // Overall timeout
            
            long kvTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            logger.info("🔑 Async KV operations completed in {} ms ({} docs)", kvTimeMs, documentKeys.size());
            DAOTimingContext.recordQueryTime(kvTimeMs);
            
            // Parse results into FHIR resources
            List<Resource> resources = new ArrayList<>();
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            
            for (int i = 0; i < futures.size(); i++) {
                try {
                    CompletableFuture<GetResult> future = futures.get(i);
                    GetResult result = future.get();
                    
                    if (result != null) {
                        String json = result.contentAsObject().toString();
                        Resource resource = (Resource) parser.parseResource(json);
                        resources.add(resource);
                    } else {
                        logger.warn("🔑 Document not found: {}", documentKeys.get(i));
                    }
                } catch (Exception e) {
                    logger.warn("🔑 Failed to retrieve/parse document {}: {}", 
                              documentKeys.get(i), e.getMessage());
                }
            }
            
            long totalTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            long parsingTimeMs = totalTimeMs - kvTimeMs;
            
            logger.info("🔑 Batch KV completed: {}/{} documents retrieved in {} ms (KV: {} ms, parsing: {} ms)", 
                       resources.size(), documentKeys.size(), totalTimeMs, kvTimeMs, parsingTimeMs);
            
            DAOTimingContext.recordParsingTime(parsingTimeMs);
            
            return resources;
            
        } catch (Exception e) {
            logger.error("❌ Batch KV retrieval failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Batch KV retrieval failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Retrieve multiple documents by their keys without parsing (raw JSON)
     * Useful for operations that don't need FHIR resource objects
     * 
     * @param documentKeys List of document keys
     * @param resourceType FHIR resource type (for collection routing)
     * @return List of raw JSON strings
     */
    public List<String> getDocumentsAsJson(List<String> documentKeys, String resourceType) {
        if (documentKeys == null || documentKeys.isEmpty()) {
            return new ArrayList<>();
        }
        
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.debug("🔑 Batch KV retrieval (raw JSON): {} documents for {}", documentKeys.size(), resourceType);
        
        try {
            // Get cached collection (avoids lookup overhead)
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            Collection collection = couchbaseGateway.getCollection("default", bucketName, DEFAULT_SCOPE, targetCollection);
            
            // Execute batch KV operations using async API (much faster than reactive)
            List<CompletableFuture<GetResult>> futures = new ArrayList<>();
            
            for (String documentKey : documentKeys) {
                CompletableFuture<GetResult> future = collection.async().get(documentKey,
                    GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
                futures.add(future);
            }
            
            // Wait for all operations and collect JSON strings
            List<String> jsonResults = new ArrayList<>();
            
            for (int i = 0; i < futures.size(); i++) {
                try {
                    GetResult result = futures.get(i).get(10, TimeUnit.SECONDS);
                    if (result != null) {
                        String json = result.contentAsObject().toString();
                        jsonResults.add(json);
                    }
                } catch (Exception e) {
                    logger.warn("🔑 Failed to retrieve document {}: {}", documentKeys.get(i), e.getMessage());
                }
            }
            
            logger.debug("🔑 Retrieved {}/{} JSON documents", jsonResults.size(), documentKeys.size());
            return jsonResults;
            
        } catch (Exception e) {
            logger.error("❌ Batch KV JSON retrieval failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Batch KV JSON retrieval failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get documents as JSON with their keys preserved (for building fullUrl in bundles)
     * Returns a Map of key -> JSON to preserve association
     */
    public Map<String, String> getDocumentsAsJsonWithKeys(List<String> documentKeys, String resourceType) {
        if (documentKeys == null || documentKeys.isEmpty()) {
            return new LinkedHashMap<>();
        }
        
        String bucketName = TenantContextHolder.getTenantId();
        
        logger.debug("🔑 Batch KV retrieval with keys (raw JSON): {} documents for {}", documentKeys.size(), resourceType);
        
        try {
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            Collection collection = couchbaseGateway.getCollection("default", bucketName, DEFAULT_SCOPE, targetCollection);
            
            List<CompletableFuture<GetResult>> futures = new ArrayList<>();
            
            for (String documentKey : documentKeys) {
                CompletableFuture<GetResult> future = collection.async().get(documentKey,
                    GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
                futures.add(future);
            }
            
            // Preserve key-to-JSON association using LinkedHashMap (maintains insertion order)
            Map<String, String> keyToJsonMap = new LinkedHashMap<>();
            
            for (int i = 0; i < futures.size(); i++) {
                String key = documentKeys.get(i);
                try {
                    GetResult result = futures.get(i).get(10, TimeUnit.SECONDS);
                    if (result != null) {
                        String json = result.contentAsObject().toString();
                        keyToJsonMap.put(key, json);
                    }
                } catch (Exception e) {
                    logger.warn("🔑 Failed to retrieve document {}: {}", key, e.getMessage());
                    // Don't add to map if retrieval failed
                }
            }
            
            logger.debug("🔑 Retrieved {}/{} JSON documents with keys", keyToJsonMap.size(), documentKeys.size());
            return keyToJsonMap;
            
        } catch (Exception e) {
            logger.error("❌ Batch KV JSON retrieval failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Batch KV JSON retrieval failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if documents exist (without retrieving content)
     * Useful for validation operations
     * 
     * @param documentKeys List of document keys to check
     * @param resourceType FHIR resource type (for collection routing)
     * @return List of booleans indicating existence (same order as input)
     */
    public List<Boolean> checkDocumentsExist(List<String> documentKeys, String resourceType) {
        if (documentKeys == null || documentKeys.isEmpty()) {
            return new ArrayList<>();
        }
        
        String bucketName = TenantContextHolder.getTenantId();
        
        try {
            // Get cached collection (avoids lookup overhead)
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            Collection collection = couchbaseGateway.getCollection("default", bucketName, DEFAULT_SCOPE, targetCollection);
            
            List<Boolean> existsResults = new ArrayList<>();
            
            for (String documentKey : documentKeys) {
                try {
                    boolean exists = collection.exists(documentKey).exists();
                    existsResults.add(exists);
                } catch (Exception e) {
                    logger.warn("🔑 Failed to check existence of {}: {}", documentKey, e.getMessage());
                    existsResults.add(false);
                }
            }
            
            logger.debug("🔑 Existence check: {}/{} documents exist", 
                        existsResults.stream().mapToInt(b -> b ? 1 : 0).sum(), 
                        documentKeys.size());
            
            return existsResults;
            
        } catch (Exception e) {
            logger.error("❌ Batch existence check failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("Batch existence check failed: " + e.getMessage(), e);
        }
    }
}
