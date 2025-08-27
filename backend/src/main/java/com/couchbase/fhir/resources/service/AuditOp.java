package com.couchbase.fhir.resources.service;

/**
 * Enumeration of FHIR audit operations for consistent audit tag generation
 */
public enum AuditOp {
    CREATE,
    UPDATE,
    DELETE
}
