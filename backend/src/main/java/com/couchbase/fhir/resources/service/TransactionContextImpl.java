package com.couchbase.fhir.resources.service;

import com.couchbase.client.java.Cluster;

/**
 * Implementation of TransactionContext for FHIR services.
 * Provides context for services to operate within existing transactions or create standalone ones.
 */
public class TransactionContextImpl implements TransactionContext {
    
    private final Cluster cluster;
    private final String bucketName;
    private final com.couchbase.client.java.transactions.TransactionAttemptContext transactionContext;
    private final boolean isInTransaction;
    
    /**
     * Create context for standalone transaction (services will create their own transaction)
     */
    public TransactionContextImpl(Cluster cluster, String bucketName) {
        this.cluster = cluster;
        this.bucketName = bucketName;
        this.transactionContext = null;
        this.isInTransaction = false;
    }
    
    /**
     * Create context for existing transaction (services will use provided transaction context)
     */
    public TransactionContextImpl(Cluster cluster, String bucketName, 
                                com.couchbase.client.java.transactions.TransactionAttemptContext transactionContext) {
        this.cluster = cluster;
        this.bucketName = bucketName;
        this.transactionContext = transactionContext;
        this.isInTransaction = true;
    }
    
    @Override
    public boolean isInTransaction() {
        return isInTransaction;
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
        return transactionContext;
    }
}
