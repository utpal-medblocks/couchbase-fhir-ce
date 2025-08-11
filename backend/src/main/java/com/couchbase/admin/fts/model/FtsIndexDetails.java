package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Simplified FTS index information for table display
 * Metrics data is now handled by dedicated metrics endpoint
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsIndexDetails {
    
    // Core fields needed for table display
    @JsonProperty("indexName")
    private String indexName;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("docsIndexed")
    private long docsIndexed;
    
    @JsonProperty("lastTimeUsed")
    private String lastTimeUsed;
    
    // Context fields needed for metrics and tree view
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("indexDefinition")
    private FtsIndex indexDefinition;
    
    // Note: All metrics (queryLatency, queryRate, totalQueries, diskSize, etc.) 
    // are now handled by the dedicated metrics endpoint
    
    // Constructor for basic info
    public FtsIndexDetails(String indexName) {
        this.indexName = indexName;
    }
}