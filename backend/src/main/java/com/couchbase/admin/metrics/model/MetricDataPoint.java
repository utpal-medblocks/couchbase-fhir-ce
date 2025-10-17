package com.couchbase.admin.metrics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Represents a single metric data point with timestamp and values for HAProxy monitoring
 */
public class MetricDataPoint {
    
    @JsonProperty("timestamp")
    private long timestamp;
    
    // Graph 2: Throughput & Concurrency
    @JsonProperty("ops")
    private double ops;
    
    @JsonProperty("scur")
    private long scur;  // Current active connections
    
    @JsonProperty("rate_max")
    private long rateMax;  // Historical peak ops/sec
    
    @JsonProperty("smax")
    private long smax;  // Maximum concurrent sessions
    
    @JsonProperty("qcur")
    private long qcur;  // Queued requests
    
    // Graph 1: Client Latency
    @JsonProperty("latency")
    private double latency;  // ttime - Total response time
    
    @JsonProperty("latency_max")
    private double latencyMax;  // ttime_max - Maximum latency spikes
    
    // Graph 3: System Resources
    @JsonProperty("cpu")
    private double cpu;  // CPU usage percentage
    
    @JsonProperty("memory")
    private double memory;  // Memory usage percentage
    
    @JsonProperty("disk")
    private double disk;  // Disk usage percentage
    
    // Graph 4: Health/Error Metrics
    @JsonProperty("hrsp_4xx")
    private long hrsp4xx;  // 4xx HTTP errors
    
    @JsonProperty("hrsp_5xx")
    private long hrsp5xx;  // 5xx HTTP errors
    
    @JsonProperty("error_percent")
    private double errorPercent;  // Calculated error percentage
    
    // JVM Details - Heap, Metaspace, Buffers, GC, Threads
    @JsonProperty("heap_used_bytes")
    private long heapUsedBytes;

    @JsonProperty("heap_max_bytes")
    private long heapMaxBytes;

    @JsonProperty("metaspace_used_bytes")
    private long metaspaceUsedBytes;

    @JsonProperty("metaspace_max_bytes")
    private long metaspaceMaxBytes;

    @JsonProperty("direct_buffer_used_bytes")
    private long directBufferUsedBytes;

    @JsonProperty("direct_buffer_count")
    private long directBufferCount;

    @JsonProperty("mapped_buffer_used_bytes")
    private long mappedBufferUsedBytes;

    @JsonProperty("mapped_buffer_count")
    private long mappedBufferCount;

    @JsonProperty("gc_pause_count_delta")
    private long gcPauseCountDelta;

    @JsonProperty("gc_pause_time_ms_delta")
    private long gcPauseTimeMsDelta;

    @JsonProperty("threads_live")
    private int threadsLive;

    // Heap generations (bytes)
    @JsonProperty("heap_young_used_bytes")
    private long heapYoungUsedBytes;

    @JsonProperty("heap_old_used_bytes")
    private long heapOldUsedBytes;

    @JsonProperty("heap_total_used_bytes")
    private long heapTotalUsedBytes;

    public MetricDataPoint() {}
    
    public MetricDataPoint(long timestamp, double ops, long scur, long rateMax, long smax, 
                          long qcur, double latency, double latencyMax, double cpu, double memory, 
                          double disk, long hrsp4xx, long hrsp5xx, double errorPercent) {
        this.timestamp = timestamp;
        this.ops = roundToTwoDecimals(ops);
        this.scur = scur;
        this.rateMax = rateMax;
        this.smax = smax;
        this.qcur = qcur;
        this.latency = latency;
        this.latencyMax = latencyMax;
        this.cpu = roundToTwoDecimals(cpu);
        this.memory = roundToTwoDecimals(memory);
        this.disk = roundToTwoDecimals(disk);
        this.hrsp4xx = hrsp4xx;
        this.hrsp5xx = hrsp5xx;
        this.errorPercent = roundToTwoDecimals(errorPercent);
    }
    
    private double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
    
    // Getters and setters
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    
    public double getOps() { return ops; }
    public void setOps(double ops) { this.ops = roundToTwoDecimals(ops); }
    
    public long getScur() { return scur; }
    public void setScur(long scur) { this.scur = scur; }
    
    public long getRateMax() { return rateMax; }
    public void setRateMax(long rateMax) { this.rateMax = rateMax; }
    
    public long getSmax() { return smax; }
    public void setSmax(long smax) { this.smax = smax; }
    
    public long getQcur() { return qcur; }
    public void setQcur(long qcur) { this.qcur = qcur; }
    
    public double getLatency() { return latency; }
    public void setLatency(double latency) { this.latency = latency; }
    
    public double getLatencyMax() { return latencyMax; }
    public void setLatencyMax(double latencyMax) { this.latencyMax = latencyMax; }
    
    public double getCpu() { return cpu; }
    public void setCpu(double cpu) { this.cpu = roundToTwoDecimals(cpu); }
    
    public double getMemory() { return memory; }
    public void setMemory(double memory) { this.memory = roundToTwoDecimals(memory); }
    
    public double getDisk() { return disk; }
    public void setDisk(double disk) { this.disk = roundToTwoDecimals(disk); }
    
    public long getHrsp4xx() { return hrsp4xx; }
    public void setHrsp4xx(long hrsp4xx) { this.hrsp4xx = hrsp4xx; }
    
    public long getHrsp5xx() { return hrsp5xx; }
    public void setHrsp5xx(long hrsp5xx) { this.hrsp5xx = hrsp5xx; }
    
    public double getErrorPercent() { return errorPercent; }
    public void setErrorPercent(double errorPercent) { this.errorPercent = roundToTwoDecimals(errorPercent); }

    public long getHeapUsedBytes() { return heapUsedBytes; }
    public void setHeapUsedBytes(long heapUsedBytes) { this.heapUsedBytes = heapUsedBytes; }

    public long getHeapMaxBytes() { return heapMaxBytes; }
    public void setHeapMaxBytes(long heapMaxBytes) { this.heapMaxBytes = heapMaxBytes; }

    public long getMetaspaceUsedBytes() { return metaspaceUsedBytes; }
    public void setMetaspaceUsedBytes(long metaspaceUsedBytes) { this.metaspaceUsedBytes = metaspaceUsedBytes; }

    public long getMetaspaceMaxBytes() { return metaspaceMaxBytes; }
    public void setMetaspaceMaxBytes(long metaspaceMaxBytes) { this.metaspaceMaxBytes = metaspaceMaxBytes; }

    public long getDirectBufferUsedBytes() { return directBufferUsedBytes; }
    public void setDirectBufferUsedBytes(long directBufferUsedBytes) { this.directBufferUsedBytes = directBufferUsedBytes; }

    public long getDirectBufferCount() { return directBufferCount; }
    public void setDirectBufferCount(long directBufferCount) { this.directBufferCount = directBufferCount; }

    public long getMappedBufferUsedBytes() { return mappedBufferUsedBytes; }
    public void setMappedBufferUsedBytes(long mappedBufferUsedBytes) { this.mappedBufferUsedBytes = mappedBufferUsedBytes; }

    public long getMappedBufferCount() { return mappedBufferCount; }
    public void setMappedBufferCount(long mappedBufferCount) { this.mappedBufferCount = mappedBufferCount; }

    public long getGcPauseCountDelta() { return gcPauseCountDelta; }
    public void setGcPauseCountDelta(long gcPauseCountDelta) { this.gcPauseCountDelta = gcPauseCountDelta; }

    public long getGcPauseTimeMsDelta() { return gcPauseTimeMsDelta; }
    public void setGcPauseTimeMsDelta(long gcPauseTimeMsDelta) { this.gcPauseTimeMsDelta = gcPauseTimeMsDelta; }

    public int getThreadsLive() { return threadsLive; }
    public void setThreadsLive(int threadsLive) { this.threadsLive = threadsLive; }

    public long getHeapYoungUsedBytes() { return heapYoungUsedBytes; }
    public void setHeapYoungUsedBytes(long heapYoungUsedBytes) { this.heapYoungUsedBytes = heapYoungUsedBytes; }

    public long getHeapOldUsedBytes() { return heapOldUsedBytes; }
    public void setHeapOldUsedBytes(long heapOldUsedBytes) { this.heapOldUsedBytes = heapOldUsedBytes; }

    public long getHeapTotalUsedBytes() { return heapTotalUsedBytes; }
    public void setHeapTotalUsedBytes(long heapTotalUsedBytes) { this.heapTotalUsedBytes = heapTotalUsedBytes; }
}
