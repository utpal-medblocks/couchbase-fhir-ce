package com.couchbase.admin.buckets.model;

import lombok.Data;

@Data
public class BucketMetricDataPoint {
    private long timestamp;
    private Double value; // Use Double to allow null for NaN values
}
