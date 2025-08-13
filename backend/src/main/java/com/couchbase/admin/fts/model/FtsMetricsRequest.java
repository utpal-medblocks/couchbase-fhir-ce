package com.couchbase.admin.fts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FtsMetricsRequest {
    private int step;
    private int timeWindow;
    private int start;
    private List<MetricFilter> metric;
    private List<String> applyFunctions;
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
        MINUTE(90, 3, "min"),        // 1.5 minutes, 10 second steps = ~9 data points
        HOUR(5400, 60, "hour"),      // 1.5 hours, 108 second steps = ~50 data points
        DAY(129600, 1440, "day"),     // 1.5 days, 2592 second steps = ~50 data points
        WEEK(907200, 10080, "week"),  // 1.5 weeks, 18144 second steps = ~50 data points
        MONTH(3931200, 43680, "month"); // 1.5 months, 78624 second steps = ~50 data points

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