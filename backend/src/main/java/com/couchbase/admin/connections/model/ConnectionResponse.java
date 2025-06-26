package com.couchbase.admin.connections.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ConnectionResponse {
    
    @JsonProperty("success")
    private boolean success;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("connectionInfo")
    private ConnectionInfo connectionInfo;
    
    @JsonProperty("error")
    private String error;
    
    @JsonProperty("clusterName")
    private String clusterName;
    
    // Default constructor
    public ConnectionResponse() {}
    
    // Constructor for success response with connection info
    public ConnectionResponse(boolean success, String message, ConnectionInfo connectionInfo) {
        this.success = success;
        this.message = message;
        this.connectionInfo = connectionInfo;
    }
    
    // Constructor for error response
    public ConnectionResponse(boolean success, String message, String error) {
        this.success = success;
        this.message = message;
        this.error = error;
    }
    
    // Static factory methods
    public static ConnectionResponse success(String message, ConnectionInfo info) {
        return new ConnectionResponse(true, message, info);
    }
    
    public static ConnectionResponse successWithClusterName(String message, String clusterName) {
        ConnectionResponse response = new ConnectionResponse();
        response.success = true;
        response.message = message;
        response.clusterName = clusterName;
        return response;
    }
    
    public static ConnectionResponse failure(String message, String error) {
        return new ConnectionResponse(false, message, error);
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
    
    public ConnectionInfo getConnectionInfo() {
        return connectionInfo;
    }
    
    public void setConnectionInfo(ConnectionInfo connectionInfo) {
        this.connectionInfo = connectionInfo;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public String getClusterName() {
        return clusterName;
    }
    
    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }
    
    // Inner class for connection information
    public static class ConnectionInfo {
        @JsonProperty("clusterVersion")
        private String clusterVersion;
        
        @JsonProperty("buckets")
        private java.util.List<String> buckets;
        
        @JsonProperty("connectedAt")
        private long connectedAt;
        
        @JsonProperty("isSSL")
        private boolean isSSL;
        
        public ConnectionInfo() {}
        
        public ConnectionInfo(String clusterVersion, java.util.List<String> buckets) {
            this.clusterVersion = clusterVersion;
            this.buckets = buckets;
            this.connectedAt = System.currentTimeMillis();
        }
        
        public ConnectionInfo(String clusterVersion, java.util.List<String> buckets, boolean isSSL) {
            this.clusterVersion = clusterVersion;
            this.buckets = buckets;
            this.connectedAt = System.currentTimeMillis();
            this.isSSL = isSSL;
        }
        
        // Getters and Setters
        public String getClusterVersion() {
            return clusterVersion;
        }
        
        public void setClusterVersion(String clusterVersion) {
            this.clusterVersion = clusterVersion;
        }
        
        public java.util.List<String> getBuckets() {
            return buckets;
        }
        
        public void setBuckets(java.util.List<String> buckets) {
            this.buckets = buckets;
        }
        
        public long getConnectedAt() {
            return connectedAt;
        }
        
        public void setConnectedAt(long connectedAt) {
            this.connectedAt = connectedAt;
        }
        
        public boolean isSSL() {
            return isSSL;
        }
        
        public void setSSL(boolean ssl) {
            isSSL = ssl;
        }
    }
} 