package com.couchbase.fhir.resources.search;

import com.couchbase.client.java.search.SearchQuery;
import java.util.List;

/**
 * Holds the result of building search queries, including both FTS queries and N1QL filters.
 * This allows us to properly handle different parameter types (FTS-compatible vs N1QL-only).
 */
public class SearchQueryResult {
    private final List<SearchQuery> ftsQueries;
    private final List<String> n1qlFilters;
    
    public SearchQueryResult(List<SearchQuery> ftsQueries, List<String> n1qlFilters) {
        this.ftsQueries = ftsQueries;
        this.n1qlFilters = n1qlFilters;
    }
    
    public List<SearchQuery> getFtsQueries() {
        return ftsQueries;
    }
    
    public List<String> getN1qlFilters() {
        return n1qlFilters;
    }
    
    public boolean hasFtsQueries() {
        return ftsQueries != null && !ftsQueries.isEmpty();
    }
    
    public boolean hasN1qlFilters() {
        return n1qlFilters != null && !n1qlFilters.isEmpty();
    }
    
    public boolean isEmpty() {
        return !hasFtsQueries() && !hasN1qlFilters();
    }
}
