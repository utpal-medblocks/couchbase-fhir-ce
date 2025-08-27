package com.couchbase.fhir.resources.service;

import java.util.Date;
import java.util.List;

/**
 * DTO for requesting meta information updates on FHIR resources
 * Provides a clean interface for specifying what meta fields should be set/updated
 */
public class MetaRequest {
    public AuditOp op;                     // required - the operation being performed
    public String userId;                  // optional - if null, will be fetched from audit service
    public Date lastUpdated;               // optional - if null, defaults to now
    public String versionId;               // optional - if null, uses op-specific default behavior
    public boolean bumpVersionIfMissing;   // for DELETE or server-driven version bump
    public List<String> profiles;          // optional - merged & de-duplicated

    // Private constructor to enforce factory methods
    private MetaRequest() {}

    /**
     * Create MetaRequest for CREATE operations
     */
    public static MetaRequest forCreate(String userId, String versionId, List<String> profiles) {
        MetaRequest r = new MetaRequest();
        r.op = AuditOp.CREATE;
        r.userId = userId;
        r.versionId = versionId;       // if null, will default to "1"
        r.profiles = profiles;
        return r;
    }

    /**
     * Create MetaRequest for CREATE operations with minimal parameters
     */
    public static MetaRequest forCreate(String userId) {
        return forCreate(userId, "1", null);
    }

    /**
     * Create MetaRequest for UPDATE operations
     */
    public static MetaRequest forUpdate(String userId, String versionId, List<String> profiles) {
        MetaRequest r = new MetaRequest();
        r.op = AuditOp.UPDATE;
        r.userId = userId;
        r.versionId = versionId;       // accept versionId from caller on PUT
        r.profiles = profiles;
        return r;
    }

    /**
     * Create MetaRequest for UPDATE operations with minimal parameters
     */
    public static MetaRequest forUpdate(String userId, String versionId) {
        return forUpdate(userId, versionId, null);
    }

    /**
     * Create MetaRequest for DELETE operations
     */
    public static MetaRequest forDelete(String userId) {
        MetaRequest r = new MetaRequest();
        r.op = AuditOp.DELETE;
        r.userId = userId;
        r.bumpVersionIfMissing = true; // common for deletes
        return r;
    }

    /**
     * Create MetaRequest for DELETE operations with version bump control
     */
    public static MetaRequest forDelete(String userId, boolean bumpVersion) {
        MetaRequest r = new MetaRequest();
        r.op = AuditOp.DELETE;
        r.userId = userId;
        r.bumpVersionIfMissing = bumpVersion;
        return r;
    }

    // Fluent builder methods for optional parameters
    public MetaRequest withLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
        return this;
    }

    public MetaRequest withProfiles(List<String> profiles) {
        this.profiles = profiles;
        return this;
    }

    public MetaRequest withVersionBump(boolean bumpVersion) {
        this.bumpVersionIfMissing = bumpVersion;
        return this;
    }
}
