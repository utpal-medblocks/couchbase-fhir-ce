package com.couchbase.admin.connections.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ConnectionRequest {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("connectionString")
    private String connectionString;
    
    @JsonProperty("username")
    private String username;
    
    @JsonProperty("password")
    private String password;
    
    @JsonProperty("bucket")
    private String bucket;
    
    @JsonProperty("scope")
    private String scope;
    
    @JsonProperty("collection")
    private String collection;
    
    @JsonProperty("serverType")
    private String serverType;
    
    @JsonProperty("sslEnabled")
    private boolean sslEnabled;
    
    // Default constructor
    public ConnectionRequest() {}
    
    // Constructor with all fields
    public ConnectionRequest(String name, String connectionString, String username, 
                           String password, String bucket, String scope, String collection,
                           String serverType, boolean sslEnabled) {
        this.name = name;
        this.connectionString = connectionString;
        this.username = username;
        this.password = password;
        this.bucket = bucket;
        this.scope = scope;
        this.collection = collection;
        this.serverType = serverType;
        this.sslEnabled = sslEnabled;
    }
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getConnectionString() {
        return connectionString;
    }
    
    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getBucket() {
        return bucket;
    }
    
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
    
    public String getScope() {
        return scope;
    }
    
    public void setScope(String scope) {
        this.scope = scope;
    }
    
    public String getCollection() {
        return collection;
    }
    
    public void setCollection(String collection) {
        this.collection = collection;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public void setServerType(String serverType) {
        this.serverType = serverType;
    }
    
    public boolean isSslEnabled() {
        return sslEnabled;
    }
    
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
    
    @Override
    public String toString() {
        return "ConnectionRequest{" +
                "name='" + name + '\'' +
                ", connectionString='" + connectionString + '\'' +
                ", username='" + username + '\'' +
                ", bucket='" + bucket + '\'' +
                ", scope='" + scope + '\'' +
                ", collection='" + collection + '\'' +
                ", serverType='" + serverType + '\'' +
                ", sslEnabled=" + sslEnabled +
                '}';
    }
} 