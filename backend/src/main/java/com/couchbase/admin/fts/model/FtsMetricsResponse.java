package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsMetricsResponse {
    private List<MetricData> data;
    private String timeRange;
    private String bucketName;
    private String indexName;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricData {
        private String name;
        private String label;
        private List<DataPoint> values;
        private String unit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPoint {
        private long timestamp;
        private Double value;
    }
}