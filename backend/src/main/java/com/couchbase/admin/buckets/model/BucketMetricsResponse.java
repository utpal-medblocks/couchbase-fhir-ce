package com.couchbase.admin.buckets.model;

import lombok.Data;
import java.util.List;

@Data
public class BucketMetricsResponse {
    private List<BucketMetricData> metrics;
    private List<Long> timestamps;
}
