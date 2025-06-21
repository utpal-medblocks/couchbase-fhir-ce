package com.couchbase.fhir.search.model;

import lombok.Data;
import java.util.Map;
import java.util.HashMap;

@Data
public class SearchCriteria {
    private String resourceType;
    private String tenant;
    private Map<String, String> parameters;
    private int page;
    private int size;
    private String sortBy;
    private String sortOrder;
    
    public SearchCriteria() {
        this.parameters = new HashMap<>();
        this.page = 1;
        this.size = 10;
        this.sortOrder = "asc";
    }
} 