package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FtsMetricsRequest {
    private int step;
    private int timeWindow;
    private int start;
    private List<MetricFilter> metric;
    private String nodesAggregation;
    private boolean alignTimestamps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricFilter {
        private String label;
        private String value;
    }

    // Time range enum for frontend
    public enum TimeRange {
        MINUTE(60, 1, "min"),
        HOUR(3600, 60, "hour"),
        DAY(86400, 600, "day"),
        WEEK(604800, 3600, "week"),
        MONTH(2592000, 21600, "month");

        private final int timeWindow;
        private final int step;
        private final String label;

        TimeRange(int timeWindow, int step, String label) {
            this.timeWindow = timeWindow;
            this.step = step;
            this.label = label;
        }

        public int getTimeWindow() { return timeWindow; }
        public int getStep() { return step; }
        public String getLabel() { return label; }
    }
}