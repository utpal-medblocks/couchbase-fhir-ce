package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.java.search.result.SearchRow;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.sort.SearchSort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Direct FTS search service that returns document keys only.
 * This replaces N1QL SEARCH queries with direct FTS calls for better performance.
 */
@Service
public class FtsSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsSearchService.class);
    
    @Autowired
    private com.couchbase.admin.connections.service.ConnectionService connectionService;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    /**
     * Execute FTS search for maximum keys (new pagination strategy)
     * Always fetches up to 1000 keys with offset=0 for optimal pagination
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param sortFields Sort fields for ordering results
     * @return FtsSearchResult containing up to 1000 document keys
     */
    public FtsSearchResult searchForAllKeys(List<SearchQuery> ftsQueries, String resourceType, 
                                          List<SearchSort> sortFields) {
        return searchForKeys(ftsQueries, resourceType, 0, 1000, sortFields);
    }
    
    /**
     * Execute FTS search and return document keys only (legacy method)
     * 
     * @param ftsQueries List of FTS search queries
     * @param resourceType FHIR resource type
     * @param from Pagination offset
     * @param size Number of results to return
     * @param sortFields Sort fields for ordering results
     * @return FtsSearchResult containing document keys and metadata
     */
    public FtsSearchResult searchForKeys(List<SearchQuery> ftsQueries, String resourceType, 
                                       int from, int size, List<SearchSort> sortFields) {
        
        try {
            // Get connection and FTS index
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) {
                throw new RuntimeException("No active connection found");
            }
            
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build FTS SearchQuery using SDK API (not JSON string)
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
                        
            // Build and log options
            SearchOptions searchOptions = buildOptions(from, size, sortFields);

            if (logger.isDebugEnabled()) {
                try {
                    String queryJson = combinedQuery.export().toString();
                    String optionsJson = exportOptions(searchOptions, from, size, sortFields);
                    logger.debug("🔍 FTS Request Payload:\n  query={}\n  options={}", queryJson, optionsJson);
                } catch (Exception e) {
                    logger.debug("🔍 Failed to export FTS request payload: {}", e.getMessage());
                }
            }

            long ftsStartTime = System.currentTimeMillis();
            SearchResult searchResult = cluster.searchQuery(ftsIndex, combinedQuery, searchOptions);
            
            long afterQueryTime = System.currentTimeMillis();            
            // Extract document keys from search results
            List<String> documentKeys = new ArrayList<>();
            for (SearchRow row : searchResult.rows()) {
                // FTS returns document IDs, which are our document keys
                String documentKey = row.id();
                documentKeys.add(documentKey);
            }
            
            long ftsElapsedTime = System.currentTimeMillis() - ftsStartTime;
            long processingTime = ftsElapsedTime - (afterQueryTime - ftsStartTime);
            logger.info("🔍 FTS search returned {} document keys for {} in {} ms (query: {} ms, processing: {} ms)", 
                       documentKeys.size(), resourceType, ftsElapsedTime, 
                       afterQueryTime - ftsStartTime, processingTime);
            
            return new FtsSearchResult(
                documentKeys,
                searchResult.metaData().metrics().totalRows(),
                searchResult.metaData().metrics().took().toMillis()
            );
            
        } catch (Exception e) {
            logger.error("❌ FTS search failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS search failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Execute FTS count query for _total=accurate operations
     */
    public long getCount(List<SearchQuery> ftsQueries, String resourceType) {
        
        try {
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) {
                throw new RuntimeException("No active connection found");
            }
            
            String ftsIndex = collectionRoutingService.getFtsIndex(resourceType);
            if (ftsIndex == null) {
                throw new IllegalArgumentException("No FTS index found for resource type: " + resourceType);
            }
            
            // Build FTS SearchQuery for count
            SearchQuery combinedQuery = buildCombinedSearchQuery(ftsQueries, resourceType);
            
            logger.debug("🔍 FTS Count Query: index={}, query={}", ftsIndex, combinedQuery);
            
            // Execute FTS search for count only and measure timing
            long ftsStartTime = System.currentTimeMillis();
            SearchOptions countOptions = SearchOptions.searchOptions()
                .timeout(Duration.ofSeconds(30))
                .limit(0)
                .includeLocations(false)
                .disableScoring(true);

            if (logger.isDebugEnabled()) {
                logger.debug("🔍 FTS Count Options: {}", exportOptions(countOptions, 0, 0, null));
            }

            SearchResult searchResult = cluster.searchQuery(ftsIndex, combinedQuery, countOptions);
            
            long totalCount = searchResult.metaData().metrics().totalRows();
            long ftsElapsedTime = System.currentTimeMillis() - ftsStartTime;
            logger.info("🔍 FTS count query returned {} total results for {} in {} ms", 
                       totalCount, resourceType, ftsElapsedTime);
            
            return totalCount;
            
        } catch (Exception e) {
            logger.error("❌ FTS count query failed for {}: {}", resourceType, e.getMessage());
            throw new RuntimeException("FTS count query failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build combined SearchQuery from individual SearchQuery objects using SDK API
     */
    private SearchQuery buildCombinedSearchQuery(List<SearchQuery> ftsQueries, String resourceType) {
        
        // Determine if resourceType filter is needed (same logic as N1QL builder)
        boolean needsResourceTypeFilter = shouldIncludeResourceTypeFilter(resourceType);
        
        List<SearchQuery> allQueries = new ArrayList<>();
        
        // Add resourceType filter if needed
        if (needsResourceTypeFilter) {
            allQueries.add(SearchQuery.match(resourceType).field("resourceType"));
        }
        
        // Add the actual search queries
        allQueries.addAll(ftsQueries);
        
        // Build the final query
        if (allQueries.isEmpty()) {
            return SearchQuery.matchAll();
        } else if (allQueries.size() == 1) {
            return allQueries.get(0);
        } else {
            // Use conjuncts (AND) to combine resourceType filter with search queries
            return SearchQuery.conjuncts(allQueries.toArray(new SearchQuery[0]));
        }
    }
    
    /**
     * Determine if resourceType filter is needed based on collection type
     */
    private boolean shouldIncludeResourceTypeFilter(String resourceType) {
        String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
        return "General".equals(targetCollection);
    }

    /**
     * Build SearchOptions with sort & standard flags.
     */
    private SearchOptions buildOptions(int from, int size, List<SearchSort> sortFields) {
        SearchOptions opts = SearchOptions.searchOptions()
            .timeout(Duration.ofSeconds(30))
            .limit(size)
            .skip(from)
            .includeLocations(false)
            .disableScoring(true);
        if (sortFields != null && !sortFields.isEmpty()) {
            // Cast to Object[] to satisfy varargs on older SDK signatures
            opts.sort((Object[]) sortFields.toArray(new SearchSort[0]));
            logger.debug("🔍 FTS: Added {} sort fields", sortFields.size());
        }
        return opts;
    }


    /**
     * Produce lightweight JSON string of options (since SDK doesn't expose export()).
     */
    private String exportOptions(SearchOptions options, int from, int size, List<SearchSort> sortFields) {
        String sorts = (sortFields == null || sortFields.isEmpty()) ? "[]" : 
            "[" + sortFields.size() + "_sort_fields]";
        return '{' +
            "\"from\":" + from + ',' +
            "\"size\":" + size + ',' +
            "\"disableScoring\":true," +
            "\"includeLocations\":false," +
            "\"sort\":" + sorts +
            '}';
    }
    
    /**
     * Result container for FTS search operations
     */
    public static class FtsSearchResult {
        private final List<String> documentKeys;
        private final long totalCount;
        private final long executionTimeMs;
        
        public FtsSearchResult(List<String> documentKeys, long totalCount, long executionTimeMs) {
            this.documentKeys = documentKeys;
            this.totalCount = totalCount;
            this.executionTimeMs = executionTimeMs;
        }
        
        public List<String> getDocumentKeys() {
            return documentKeys;
        }
        
        public long getTotalCount() {
            return totalCount;
        }
        
        public long getExecutionTimeMs() {
            return executionTimeMs;
        }
        
        public boolean isEmpty() {
            return documentKeys.isEmpty();
        }
        
        public int size() {
            return documentKeys.size();
        }
    }
}
