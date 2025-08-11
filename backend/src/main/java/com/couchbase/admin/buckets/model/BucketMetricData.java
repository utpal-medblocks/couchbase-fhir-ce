package com.couchbase.admin.buckets.model;

import lombok.Data;
import java.util.List;

@Data
public class BucketMetricData {
    private String name;
    private String label;
    private String unit;
    private List<BucketMetricDataPoint> dataPoints;
}
