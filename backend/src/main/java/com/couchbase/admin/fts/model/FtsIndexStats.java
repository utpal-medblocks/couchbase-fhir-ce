package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsIndexStats {
    
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
    
    // Additional stats fields from nsstats
    @JsonProperty("numFilesOnDisk")
    private long numFilesOnDisk;
    
    @JsonProperty("numBytesUsedDisk")
    private long numBytesUsedDisk;
    
    @JsonProperty("numItemsIntroduced")
    private long numItemsIntroduced;
    
    @JsonProperty("numItemsUpserted")
    private long numItemsUpserted;
    
    @JsonProperty("numItemsDeleted")
    private long numItemsDeleted;
    
    @JsonProperty("numRootMemorySegments")
    private long numRootMemorySegments;
    
    @JsonProperty("numPersistedSegments")
    private long numPersistedSegments;
    
    @JsonProperty("totalKVOps")
    private long totalKVOps;
    
    @JsonProperty("totalQueries")
    private long totalQueriesDetailed;
    
    @JsonProperty("totalQueriesError")
    private long totalQueriesError;
    
    @JsonProperty("totalQueriesTimeout")
    private long totalQueriesTimeout;
    
    @JsonProperty("totalRequestTime")
    private long totalRequestTime;
    
    @JsonProperty("avgQueryLatency")
    private double avgQueryLatency;
}