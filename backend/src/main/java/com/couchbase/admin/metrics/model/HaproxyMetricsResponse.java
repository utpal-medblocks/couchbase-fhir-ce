package com.couchbase.admin.metrics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Response model for HAProxy time-series metrics
 */
public class HaproxyMetricsResponse {
    
    @JsonProperty("minute")
    private List<MetricDataPoint> minute;
    
    @JsonProperty("hour")
    private List<MetricDataPoint> hour;
    
    @JsonProperty("day")
    private List<MetricDataPoint> day;
    
    @JsonProperty("week")
    private List<MetricDataPoint> week;
    
    @JsonProperty("month")
    private List<MetricDataPoint> month;
    
    @JsonProperty("current")
    private Map<String, Object> current;
    
    @JsonProperty("timestamp")
    private long timestamp;
    
    public HaproxyMetricsResponse() {}
    
    // Getters and setters
    public List<MetricDataPoint> getMinute() { return minute; }
    public void setMinute(List<MetricDataPoint> minute) { this.minute = minute; }
    
    public List<MetricDataPoint> getHour() { return hour; }
    public void setHour(List<MetricDataPoint> hour) { this.hour = hour; }
    
    public List<MetricDataPoint> getDay() { return day; }
    public void setDay(List<MetricDataPoint> day) { this.day = day; }
    
    public List<MetricDataPoint> getWeek() { return week; }
    public void setWeek(List<MetricDataPoint> week) { this.week = week; }
    
    public List<MetricDataPoint> getMonth() { return month; }
    public void setMonth(List<MetricDataPoint> month) { this.month = month; }
    
    public Map<String, Object> getCurrent() { return current; }
    public void setCurrent(Map<String, Object> current) { this.current = current; }
    
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
