package com.couchbase.admin.buckets.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Bucket {
    private String name;
    private String type;
    private long memoryQuota;
    private long memoryUsed;
    private int itemCount;
    private String status;
    private long diskUsed;
    private long diskQuota;
} 