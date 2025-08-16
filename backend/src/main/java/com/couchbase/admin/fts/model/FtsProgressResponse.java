package com.couchbase.admin.fts.model;

import java.util.List;

/**
 * Response model for FTS progress API
 */
public class FtsProgressResponse {
    private List<FtsProgressData> results;

    // Constructors
    public FtsProgressResponse() {}

    public FtsProgressResponse(List<FtsProgressData> results) {
        this.results = results;
    }

    // Getters and Setters
    public List<FtsProgressData> getResults() {
        return results;
    }

    public void setResults(List<FtsProgressData> results) {
        this.results = results;
    }

    @Override
    public String toString() {
        return "FtsProgressResponse{" +
                "results=" + results +
                '}';
    }
}
