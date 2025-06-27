package com.couchbase.admin.buckets.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Detailed bucket information for FHIR-enabled buckets
 */
public class BucketDetails {
    
    @JsonProperty("bucketName")
    private String bucketName;
    
    @JsonProperty("bucketType")
    private String bucketType;
    
    @JsonProperty("storageBackend")
    private String storageBackend;
    
    @JsonProperty("evictionPolicy")
    private String evictionPolicy;
    
    @JsonProperty("itemCount")
    private long itemCount;
    
    @JsonProperty("opsPerSec")
    private double opsPerSec;
    
    @JsonProperty("replicaNumber")
    private int replicaNumber;
    
    @JsonProperty("ram")
    private long ram;
    
    @JsonProperty("diskUsed")
    private long diskUsed;
    
    @JsonProperty("durabilityMinLevel")
    private String durabilityMinLevel;
    
    @JsonProperty("conflictResolutionType")
    private String conflictResolutionType;
    
    @JsonProperty("maxTTL")
    private long maxTTL;
    
    @JsonProperty("quotaPercentUsed")
    private double quotaPercentUsed;
    
    @JsonProperty("residentRatio")
    private double residentRatio;
    
    @JsonProperty("cacheHit")
    private double cacheHit;
    
    @JsonProperty("collectionMetrics")
    private Map<String, Map<String, Object>> collectionMetrics;
    
    // Constructors
    public BucketDetails() {}
    
    public BucketDetails(String bucketName) {
        this.bucketName = bucketName;
    }
    
    // Getters and Setters
    public String getBucketName() {
        return bucketName;
    }
    
    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
    
    public String getBucketType() {
        return bucketType;
    }
    
    public void setBucketType(String bucketType) {
        this.bucketType = bucketType;
    }
    
    public String getStorageBackend() {
        return storageBackend;
    }
    
    public void setStorageBackend(String storageBackend) {
        this.storageBackend = storageBackend;
    }
    
    public String getEvictionPolicy() {
        return evictionPolicy;
    }
    
    public void setEvictionPolicy(String evictionPolicy) {
        this.evictionPolicy = evictionPolicy;
    }
    
    public long getItemCount() {
        return itemCount;
    }
    
    public void setItemCount(long itemCount) {
        this.itemCount = itemCount;
    }
    
    public double getOpsPerSec() {
        return opsPerSec;
    }
    
    public void setOpsPerSec(double opsPerSec) {
        this.opsPerSec = opsPerSec;
    }
    
    public int getReplicaNumber() {
        return replicaNumber;
    }
    
    public void setReplicaNumber(int replicaNumber) {
        this.replicaNumber = replicaNumber;
    }
    
    public long getRam() {
        return ram;
    }
    
    public void setRam(long ram) {
        this.ram = ram;
    }
    
    public long getDiskUsed() {
        return diskUsed;
    }
    
    public void setDiskUsed(long diskUsed) {
        this.diskUsed = diskUsed;
    }
    
    public String getDurabilityMinLevel() {
        return durabilityMinLevel;
    }
    
    public void setDurabilityMinLevel(String durabilityMinLevel) {
        this.durabilityMinLevel = durabilityMinLevel;
    }
    
    public String getConflictResolutionType() {
        return conflictResolutionType;
    }
    
    public void setConflictResolutionType(String conflictResolutionType) {
        this.conflictResolutionType = conflictResolutionType;
    }
    
    public long getMaxTTL() {
        return maxTTL;
    }
    
    public void setMaxTTL(long maxTTL) {
        this.maxTTL = maxTTL;
    }
    
    public double getQuotaPercentUsed() {
        return quotaPercentUsed;
    }
    
    public void setQuotaPercentUsed(double quotaPercentUsed) {
        this.quotaPercentUsed = quotaPercentUsed;
    }
    
    public double getResidentRatio() {
        return residentRatio;
    }
    
    public void setResidentRatio(double residentRatio) {
        this.residentRatio = residentRatio;
    }
    
    public double getCacheHit() {
        return cacheHit;
    }
    
    public void setCacheHit(double cacheHit) {
        this.cacheHit = cacheHit;
    }
    
    public Map<String, Map<String, Object>> getCollectionMetrics() {
        return collectionMetrics;
    }
    
    public void setCollectionMetrics(Map<String, Map<String, Object>> collectionMetrics) {
        this.collectionMetrics = collectionMetrics;
    }
    
    @Override
    public String toString() {
        return "BucketDetails{" +
                "bucketName='" + bucketName + '\'' +
                ", bucketType='" + bucketType + '\'' +
                ", itemCount=" + itemCount +
                ", opsPerSec=" + opsPerSec +
                ", quotaPercentUsed=" + quotaPercentUsed +
                '}';
    }
}
