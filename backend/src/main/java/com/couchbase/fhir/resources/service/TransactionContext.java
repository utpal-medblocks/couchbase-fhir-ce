package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.Cluster;

/**
 * Context interface for handling both standalone and nested transaction scenarios.
 * Used by PUT and DELETE services to determine whether to create their own transaction
 * or operate within an existing Bundle transaction.
 */
public interface TransactionContext {
    
    /**
     * Check if we're currently operating within a transaction
     */
    boolean isInTransaction();
    
    /**
     * Get the Couchbase cluster connection
     */
    Cluster getCluster();
    
    /**
     * Get the target bucket name
     */
    String getBucketName();
    
    /**
     * Get the transaction context (only available when isInTransaction() is true)
     */
    com.couchbase.client.java.transactions.TransactionAttemptContext getTransactionContext();
    
    /**
     * Create a standalone transaction context (for individual operations)
     */
    static TransactionContext standalone(Cluster cluster, String bucketName) {
        return new StandaloneTransactionContext(cluster, bucketName);
    }
    
    /**
     * Create a nested transaction context (for Bundle operations)
     */
    static TransactionContext nested(Cluster cluster, String bucketName, 
                                   com.couchbase.client.java.transactions.TransactionAttemptContext txContext) {
        return new NestedTransactionContext(cluster, bucketName, txContext);
    }
}

/**
 * Implementation for standalone operations (PUT/DELETE outside of Bundle)
 */
class StandaloneTransactionContext implements TransactionContext {
    private final Cluster cluster;
    private final String bucketName;
    
    public StandaloneTransactionContext(Cluster cluster, String bucketName) {
        this.cluster = cluster;
        this.bucketName = bucketName;
    }
    
    @Override
    public boolean isInTransaction() {
        return false; // Will create its own transaction
    }
    
    @Override
    public Cluster getCluster() {
        return cluster;
    }
    
    @Override
    public String getBucketName() {
        return bucketName;
    }
    
    @Override
    public com.couchbase.client.java.transactions.TransactionAttemptContext getTransactionContext() {
        throw new UnsupportedOperationException("Not in transaction - use isInTransaction() first");
    }
}

/**
 * Implementation for nested operations (PUT/DELETE within Bundle transaction)
 */
class NestedTransactionContext implements TransactionContext {
    private final Cluster cluster;
    private final String bucketName;
    private final com.couchbase.client.java.transactions.TransactionAttemptContext txContext;
    
    public NestedTransactionContext(Cluster cluster, String bucketName, 
                                  com.couchbase.client.java.transactions.TransactionAttemptContext txContext) {
        this.cluster = cluster;
        this.bucketName = bucketName;
        this.txContext = txContext;
    }
    
    @Override
    public boolean isInTransaction() {
        return true; // Already in Bundle transaction
    }
    
    @Override
    public Cluster getCluster() {
        return cluster;
    }
    
    @Override
    public String getBucketName() {
        return bucketName;
    }
    
    @Override
    public com.couchbase.client.java.transactions.TransactionAttemptContext getTransactionContext() {
        return txContext;
    }
}
