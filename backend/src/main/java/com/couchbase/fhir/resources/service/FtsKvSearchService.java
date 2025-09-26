package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.interceptor.DAOTimingContext;
import com.couchbase.fhir.resources.interceptor.RequestPerfBagUtils;
import com.couchbase.fhir.resources.service.FtsSearchService.FtsSearchResult;
import com.couchbase.fhir.resources.util.Ftsn1qlQueryBuilder.SortField;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.rest.api.server.RequestDetails;

import java.util.List;

/**
 * Combined FTS/KV search service that orchestrates the new architecture:
 * 1. Execute FTS search to get document keys
 * 2. Use batch KV operations to retrieve documents
 * 3. Return parsed FHIR resources
 * 
 * This replaces the N1QL SEARCH approach with direct FTS + KV operations.
 */
@Service
public class FtsKvSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsKvSearchService.class);
    
    @Autowired
    private FtsSearchService ftsSearchService;
    
    @Autowired
    private BatchKvService batchKvService;
    
    /**
     * Execute search using FTS + KV architecture
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param from Pagination offset
     * @param size Number of results to return
     * @param sortFields Sort fields for ordering results
     * @return List of FHIR resources
     */
    public List<Resource> search(List<SearchQuery> ftsQueries, String resourceType, 
                               int from, int size, List<SortField> sortFields) {
        
        logger.info("🚀 FTS/KV Search: {} with {} queries, from={}, size={}", 
                   resourceType, ftsQueries.size(), from, size);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Step 1: Execute FTS search to get document keys
            FtsSearchResult ftsResult = ftsSearchService.searchForKeys(ftsQueries, resourceType, from, size, sortFields);
            
            if (ftsResult.isEmpty()) {
                logger.info("🚀 FTS returned no results for {}", resourceType);
                return List.of();
            }
            
            logger.info("🚀 FTS found {} document keys for {}", ftsResult.size(), resourceType);
            
            // Step 2: Retrieve documents using batch KV operations
            List<Resource> resources = batchKvService.getDocuments(ftsResult.getDocumentKeys(), resourceType);
            
            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("🚀 FTS/KV Search completed: {}/{} resources retrieved in {} ms", 
                       resources.size(), ftsResult.size(), totalTime);
            
            return resources;
            
        } catch (Exception e) {
            logger.error("❌ FTS/KV search failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS/KV search failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute search with detailed timing for performance monitoring
     */
    public List<Resource> searchWithTiming(List<SearchQuery> ftsQueries, String resourceType, 
                                         int from, int size, List<SortField> sortFields,
                                         RequestDetails requestDetails) {
        
        logger.info("🚀 FTS/KV Search (with timing): {} with {} queries", resourceType, ftsQueries.size());
        
        long searchStartTime = System.currentTimeMillis();
        
        try {
            // Start timing context for detailed breakdown
            DAOTimingContext.start();
            
            // Step 1: Execute FTS search
            long ftsStartTime = System.currentTimeMillis();
            FtsSearchResult ftsResult = ftsSearchService.searchForKeys(ftsQueries, resourceType, from, size, sortFields);
            long ftsTime = System.currentTimeMillis() - ftsStartTime;
            
            if (requestDetails != null) {
                RequestPerfBagUtils.addTiming(requestDetails, "fts_search", ftsTime);
            }
            
            if (ftsResult.isEmpty()) {
                logger.info("🚀 FTS returned no results for {}", resourceType);
                return List.of();
            }
            
            // Step 2: Batch KV retrieval (timing is handled inside BatchKvService)
            List<Resource> resources = batchKvService.getDocuments(ftsResult.getDocumentKeys(), resourceType);
            
            // Get detailed timing breakdown from DAO context
            DAOTimingContext timingContext = DAOTimingContext.getAndClear();
            if (timingContext != null && requestDetails != null) {
                RequestPerfBagUtils.addTiming(requestDetails, "kv_operations", timingContext.getQueryExecutionMs());
                RequestPerfBagUtils.addTiming(requestDetails, "hapi_parsing", timingContext.getHapiParsingMs());
            }
            
            long totalTime = System.currentTimeMillis() - searchStartTime;
            
            if (requestDetails != null) {
                RequestPerfBagUtils.addTiming(requestDetails, "total_search", totalTime);
                RequestPerfBagUtils.addCount(requestDetails, "fts_kv_calls", 1);
                RequestPerfBagUtils.addCount(requestDetails, "documents_retrieved", resources.size());
            }
            
            logger.info("🚀 FTS/KV Search completed: {}/{} resources in {} ms (FTS: {} ms)", 
                       resources.size(), ftsResult.size(), totalTime, ftsTime);
            
            return resources;
            
        } catch (Exception e) {
            logger.error("❌ FTS/KV search with timing failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS/KV search failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get count using FTS (for _total=accurate operations)
     */
    public long getCount(List<SearchQuery> ftsQueries, String resourceType) {
        logger.debug("🚀 FTS/KV Count: {} with {} queries", resourceType, ftsQueries.size());
        
        try {
            long count = ftsSearchService.getCount(ftsQueries, resourceType);
            logger.debug("🚀 FTS count returned {} for {}", count, resourceType);
            return count;
            
        } catch (Exception e) {
            logger.error("❌ FTS/KV count failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS/KV count failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute ID-only search (for conditional operations like resolveOne)
     * Returns only the document keys without retrieving full documents
     */
    public List<String> searchForIds(List<SearchQuery> ftsQueries, String resourceType, int limit) {
        logger.debug("🚀 FTS/KV ID-only search: {} with {} queries, limit={}", resourceType, ftsQueries.size(), limit);
        
        try {
            FtsSearchResult ftsResult = ftsSearchService.searchForKeys(ftsQueries, resourceType, 0, limit, null);
            
            logger.debug("🚀 FTS ID-only search returned {} keys for {}", ftsResult.size(), resourceType);
            return ftsResult.getDocumentKeys();
            
        } catch (Exception e) {
            logger.error("❌ FTS/KV ID-only search failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS/KV ID-only search failed: " + e.getMessage(), e);
        }
    }
}
