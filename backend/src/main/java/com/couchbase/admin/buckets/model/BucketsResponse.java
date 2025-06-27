package com.couchbase.admin.buckets.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response wrapper for bucket operations
 */
public class BucketsResponse {
    
    @JsonProperty("success")
    private boolean success;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("buckets")
    private List<BucketDetails> buckets;
    
    @JsonProperty("bucketNames")
    private List<String> bucketNames;
    
    @JsonProperty("count")
    private int count;
    
    // Constructors
    public BucketsResponse() {}
    
    public BucketsResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
    
    public static BucketsResponse success(List<BucketDetails> buckets) {
        BucketsResponse response = new BucketsResponse(true, "Successfully retrieved bucket details");
        response.setBuckets(buckets);
        response.setCount(buckets.size());
        return response;
    }
    
    public static BucketsResponse successNames(List<String> bucketNames) {
        BucketsResponse response = new BucketsResponse(true, "Successfully retrieved bucket names");
        response.setBucketNames(bucketNames);
        response.setCount(bucketNames.size());
        return response;
    }
    
    public static BucketsResponse error(String message) {
        return new BucketsResponse(false, message);
    }
    
    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public List<BucketDetails> getBuckets() {
        return buckets;
    }
    
    public void setBuckets(List<BucketDetails> buckets) {
        this.buckets = buckets;
    }
    
    public List<String> getBucketNames() {
        return bucketNames;
    }
    
    public void setBucketNames(List<String> bucketNames) {
        this.bucketNames = bucketNames;
    }
    
    public int getCount() {
        return count;
    }
    
    public void setCount(int count) {
        this.count = count;
    }
}
