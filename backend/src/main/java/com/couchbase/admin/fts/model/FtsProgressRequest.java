package com.couchbase.admin.fts.model;

import java.util.List;

/**
 * Request model for FTS progress API
 */
public class FtsProgressRequest {
    private String connectionName;
    private List<String> indexNames;
    private String bucketName;
    private String scopeName;

    // Constructors
    public FtsProgressRequest() {}

    public FtsProgressRequest(String connectionName, List<String> indexNames) {
        this.connectionName = connectionName;
        this.indexNames = indexNames;
    }

    public FtsProgressRequest(String connectionName, List<String> indexNames, String bucketName, String scopeName) {
        this.connectionName = connectionName;
        this.indexNames = indexNames;
        this.bucketName = bucketName;
        this.scopeName = scopeName;
    }

    // Getters and Setters
    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public List<String> getIndexNames() {
        return indexNames;
    }

    public void setIndexNames(List<String> indexNames) {
        this.indexNames = indexNames;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    @Override
    public String toString() {
        return "FtsProgressRequest{" +
                "connectionName='" + connectionName + '\'' +
                ", indexNames=" + indexNames +
                ", bucketName='" + bucketName + '\'' +
                ", scopeName='" + scopeName + '\'' +
                '}';
    }
}
