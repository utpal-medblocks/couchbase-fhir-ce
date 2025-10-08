package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.fhir.resources.service.FtsSearchService.FtsSearchResult;
import com.couchbase.client.java.search.sort.SearchSort;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
     * Execute FTS search for all keys (new pagination strategy)
     * Always fetches up to 1000 keys, then returns only the requested page
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param pageSize User-requested page size
     * @param sortFields Sort fields for ordering results
     * @return FtsSearchResult with all keys (up to 1000) for pagination state
     */
    public FtsSearchResult searchForAllKeys(List<SearchQuery> ftsQueries, String resourceType, 
                                          List<SearchSort> sortFields) {
        
        logger.info("🚀 FTS/KV New Pagination: {} with {} queries, fetching up to 1000 keys", 
                   resourceType, ftsQueries.size());
        
        // Always fetch maximum keys (1000) with offset=0 for optimal pagination
        return ftsSearchService.searchForAllKeys(ftsQueries, resourceType, sortFields);
    }
    
    /**
     * Retrieve documents using KV-only operations from stored keys (new pagination)
     * Used for pages 2+ where we already have all document keys
     * 
     * @param documentKeys List of document keys to retrieve
     * @param resourceType FHIR resource type
     * @return List of FHIR resources
     */
    public List<Resource> getDocumentsFromKeys(List<String> documentKeys, String resourceType) {
        
        logger.info("🔑 KV-Only Pagination: {} documents for {}", documentKeys.size(), resourceType);
        
        if (documentKeys.isEmpty()) {
            return List.of();
        }
        
        // Pure KV operation - no FTS involved
        return batchKvService.getDocuments(documentKeys, resourceType);
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
    
}
