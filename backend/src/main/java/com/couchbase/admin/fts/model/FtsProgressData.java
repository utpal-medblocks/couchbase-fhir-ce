package com.couchbase.admin.fts.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model representing FTS index progress data from CB Console API
 */
public class FtsProgressData {
    private String indexName;
    private long docCount;
    private long totSeqReceived;
    private long numMutationsToIndex;
    private String ingestStatus;
    private String error;

    // Constructors
    public FtsProgressData() {}

    public FtsProgressData(String indexName) {
        this.indexName = indexName;
    }

    // Getters and Setters
    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    @JsonProperty("doc_count")
    public long getDocCount() {
        return docCount;
    }

    public void setDocCount(long docCount) {
        this.docCount = docCount;
    }

    public long getTotSeqReceived() {
        return totSeqReceived;
    }

    public void setTotSeqReceived(long totSeqReceived) {
        this.totSeqReceived = totSeqReceived;
    }

    public long getNumMutationsToIndex() {
        return numMutationsToIndex;
    }

    public void setNumMutationsToIndex(long numMutationsToIndex) {
        this.numMutationsToIndex = numMutationsToIndex;
    }

    @JsonProperty("ingest_status")
    public String getIngestStatus() {
        return ingestStatus;
    }

    public void setIngestStatus(String ingestStatus) {
        this.ingestStatus = ingestStatus;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "FtsProgressData{" +
                "indexName='" + indexName + '\'' +
                ", docCount=" + docCount +
                ", totSeqReceived=" + totSeqReceived +
                ", numMutationsToIndex=" + numMutationsToIndex +
                ", ingestStatus='" + ingestStatus + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}
