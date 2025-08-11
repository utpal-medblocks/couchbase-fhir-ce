package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsIndex {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("uuid")
    private String uuid;
    
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("params")
    private Object params;
    
    @JsonProperty("sourceType")
    private String sourceType;
    
    @JsonProperty("sourceName")
    private String sourceName;
    
    @JsonProperty("sourceUUID")
    private String sourceUUID;
    
    @JsonProperty("planParams")
    private Object planParams;
}