package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.sort.SearchSort;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling FHIR resource version history operations.
 * 
 * Implements:
 * - GET {resourceType}/{id}/_history/{vid} - Get specific version (KV operation)
 * - GET {resourceType}/{id}/_history - Get all versions (returns List<Resource> for HAPI)
 * 
 * Strategy:
 * 1. Specific version: Direct KV GET from Versions collection with key {resourceType}/{id}/{vid}
 * 2. All versions: 
 *    - KV GET current version from resource collection
 *    - FTS search on Versions collection for all historical versions
 *    - Batch KV GET all version documents
 *    - Return list with proper IdType (HAPI handles bundle creation)
 */
@Service
public class HistoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    private static final String VERSIONS_COLLECTION = "Versions";
    private static final String VERSIONS_FTS_INDEX = "ftsVersions";
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 50;
    private static final int MAX_HISTORY_SIZE = 1000;
    
    @Autowired
    private com.couchbase.fhir.resources.gateway.CouchbaseGateway couchbaseGateway;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    @Autowired
    private FhirContext fhirContext;
    
    @Autowired
    private FtsSearchService ftsSearchService;
    
    /**
     * Get a specific version of a resource (vread operation)
     * GET {resourceType}/{id}/_history/{vid}
     * 
     * Uses direct KV operation on Versions collection with key: {resourceType}/{id}/{vid}
     */
    public Resource getResourceVersion(String resourceType, String id, String versionId, String bucketName) {
        logger.debug("📜 Getting specific version: {}/{} version {}", resourceType, id, versionId);
        
        try {
            String connectionName = "default";
            Cluster cluster = couchbaseGateway.getClusterForTransaction(connectionName);
            
            // Key format in Versions collection: {resourceType}/{id}/{versionId}
            String versionKey = resourceType + "/" + id + "/" + versionId;
            
            // Get from Versions collection
            Collection collection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(VERSIONS_COLLECTION);
            
            logger.debug("📜 KV GET: bucket={}, collection={}, key={}", bucketName, VERSIONS_COLLECTION, versionKey);
            
            GetResult result = collection.get(versionKey, 
                GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
            JsonObject json = result.contentAsObject();
            
            // Parse into FHIR resource
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            Resource resource = (Resource) parser.parseResource(json.toString());
            
            logger.debug("✅ Retrieved version {}/{} v{}", resourceType, id, versionId);
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ Failed to get version {}/{} v{}: {}", resourceType, id, versionId, e.getMessage());
            throw new ResourceNotFoundException("Version not found: " + resourceType + "/" + id + "/_history/" + versionId);
        }
    }
    
    /**
     * Get complete version history for a resource as a list (for HAPI @History annotation)
     * Returns List<Resource> with proper IdType including version - HAPI will create the bundle
     * 
     * GET {resourceType}/{id}/_history
     */
    public List<Resource> getResourceHistoryResources(String resourceType, String id, Integer count,
                                                       Instant since, String bucketName) {
        logger.debug("📜 Getting history for {}/{} (count={}, since={})", resourceType, id, count, since);
        
        int pageSize = (count != null && count > 0) ? Math.min(count, MAX_HISTORY_SIZE) : DEFAULT_HISTORY_PAGE_SIZE;
        
        try {
            // Step 1: Get current version from resource collection
            Resource currentResource = getCurrentResource(resourceType, id, bucketName);
            List<Resource> result = new ArrayList<>();
            
            // Add current version with proper versioned IdType
            if (currentResource.getMeta() != null && currentResource.getMeta().getVersionId() != null) {
                String versionId = currentResource.getMeta().getVersionId();
                currentResource.setId(new org.hl7.fhir.r4.model.IdType(resourceType, id, versionId));
                result.add(currentResource);
            }
            
            // Step 2: Get historical versions from Versions collection
            List<String> historicalKeys = searchHistoricalVersions(resourceType, id, since, bucketName);
            
            logger.debug("📜 Found {} historical versions for {}/{}", historicalKeys.size(), resourceType, id);
            
            // Limit if needed
            if (historicalKeys.size() > pageSize - result.size()) {
                historicalKeys = historicalKeys.subList(0, Math.max(0, pageSize - result.size()));
            }
            
            // Step 3: Fetch and add historical versions
            if (!historicalKeys.isEmpty()) {
                List<Resource> historical = fetchVersionDocuments(historicalKeys, resourceType, id, bucketName);
                for (Resource r : historical) {
                    String versionId = r.getMeta() != null ? r.getMeta().getVersionId() : null;
                    if (versionId != null) {
                        r.setId(new org.hl7.fhir.r4.model.IdType(resourceType, id, versionId));
                    }
                }
                result.addAll(historical);
            }
            
            logger.debug("✅ Returning {} versions for {}/{}", result.size(), resourceType, id);
            return result;
            
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            logger.error("❌ Failed to get history for {}/{}: {}", resourceType, id, e.getMessage());
            throw new RuntimeException("Failed to retrieve resource history: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get current resource from main collection
     */
    private Resource getCurrentResource(String resourceType, String id, String bucketName) {
        try {
            String connectionName = "default";
            Cluster cluster = couchbaseGateway.getClusterForTransaction(connectionName);
            
            String documentKey = resourceType + "/" + id;
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            Collection collection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(targetCollection);
            
            GetResult result = collection.get(documentKey, 
                GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
            JsonObject json = result.contentAsObject();
            
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            Resource resource = (Resource) parser.parseResource(json.toString());
            
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ Current resource not found: {}/{}", resourceType, id);
            throw new ResourceNotFoundException("Resource not found: " + resourceType + "/" + id);
        }
    }
    
    /**
     * Search for historical versions in Versions collection using FTS
     */
    private List<String> searchHistoricalVersions(String resourceType, String id, Instant since, String bucketName) {
        try {
            // Build FTS query: match resourceType AND match id
            List<SearchQuery> ftsQueries = new ArrayList<>();
            ftsQueries.add(SearchQuery.match(resourceType).field("resourceType"));
            ftsQueries.add(SearchQuery.match(id).field("id"));
            
            // Add date range filter if _since parameter provided
            if (since != null) {
                String sinceStr = since.toString();
                ftsQueries.add(SearchQuery.dateRange()
                    .start(sinceStr, true)
                    .field("meta.lastUpdated"));
            }
            
            // Sort by meta.lastUpdated descending (newest first)
            List<SearchSort> sortFields = new ArrayList<>();
            sortFields.add(SearchSort.byField("meta.lastUpdated").desc(true));
            
            // Execute FTS search to get all version keys
            FtsSearchService.FtsSearchResult ftsResult = ftsSearchService.searchForAllKeysInCollection(
                ftsQueries, VERSIONS_FTS_INDEX, sortFields, bucketName);
            
            return ftsResult.getDocumentKeys();
            
        } catch (Exception e) {
            logger.warn("📜 FTS search for historical versions failed (returning empty): {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Fetch version documents using batch KV operations
     * Handles both current versions (simple key) and historical versions (key with version suffix)
     */
    private List<Resource> fetchVersionDocuments(List<String> versionKeys, String resourceType, 
                                                 String id, String bucketName) {
        List<Resource> resources = new ArrayList<>();
        
        try {
            String connectionName = "default";
            Cluster cluster = couchbaseGateway.getClusterForTransaction(connectionName);
            
            // Fetch historical versions from Versions collection
            Collection versionsCollection = cluster.bucket(bucketName)
                    .scope(DEFAULT_SCOPE)
                    .collection(VERSIONS_COLLECTION);
            
            List<CompletableFuture<GetResult>> futures = new ArrayList<>();
            for (String key : versionKeys) {
                CompletableFuture<GetResult> future = versionsCollection.async().get(key,
                    GetOptions.getOptions().timeout(Duration.ofSeconds(10)));
                futures.add(future);
            }
            
            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);
            
            // Parse results
            JsonParser parser = (JsonParser) fhirContext.newJsonParser();
            parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
            
            for (int i = 0; i < futures.size(); i++) {
                try {
                    GetResult result = futures.get(i).get(1, TimeUnit.SECONDS);
                    if (result != null) {
                        JsonObject json = result.contentAsObject();
                        Resource resource = (Resource) parser.parseResource(json.toString());
                        resources.add(resource);
                    }
                } catch (Exception e) {
                    logger.warn("📜 Failed to fetch historical version {}: {}", 
                               versionKeys.get(i), e.getMessage());
                }
            }
            
            logger.debug("📜 Fetched {} version documents", resources.size());
            return resources;
            
        } catch (Exception e) {
            logger.error("❌ Failed to fetch version documents: {}", e.getMessage());
            return resources;
        }
    }
}
