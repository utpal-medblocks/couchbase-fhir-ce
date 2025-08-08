package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Combined FTS index information including definition and statistics
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsIndexDetails {
    
    @JsonProperty("indexName")
    private String indexName;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("docsIndexed")
    private long docsIndexed;
    
    @JsonProperty("lastTimeUsed")
    private String lastTimeUsed;
    
    @JsonProperty("queryLatency")
    private double queryLatency;
    
    @JsonProperty("queryRate")
    private double queryRate;
    
    @JsonProperty("totalQueries")
    private long totalQueries;
    
    @JsonProperty("diskSize")
    private long diskSize;
    
    // Index definition details
    @JsonProperty("indexDefinition")
    private FtsIndex indexDefinition;
    
    // Bucket and scope information
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("scopeName")
    private String scopeName;
    
    // Additional performance metrics
    @JsonProperty("avgQueryLatency")
    private double avgQueryLatency;
    
    @JsonProperty("numFilesOnDisk")
    private long numFilesOnDisk;
    
    @JsonProperty("totalQueriesError")
    private long totalQueriesError;
    
    @JsonProperty("totalQueriesTimeout")
    private long totalQueriesTimeout;
    
    // Constructor for basic info
    public FtsIndexDetails(String indexName) {
        this.indexName = indexName;
    }
}