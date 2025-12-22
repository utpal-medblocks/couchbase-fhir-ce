package com.couchbase.admin.users.bulkGroup.dto;

import com.couchbase.admin.users.bulkGroup.model.BulkGroup;

import java.time.Instant;
import java.util.List;

/**
 * DTO for exposing BulkGroup through REST APIs
 */
public class BulkGroupResponse {
    private String id;
    private List<String> patientIds;
    private Instant createdAt;

    public static BulkGroupResponse from(BulkGroup g) {
        if (g == null) return null;
        BulkGroupResponse r = new BulkGroupResponse();
        r.id = g.getId();
        r.patientIds = g.getPatientIds();
        r.createdAt = g.getCreatedAt();
        return r;
    }

    public String getId() { return id; }
    public List<String> getPatientIds() { return patientIds; }
    public Instant getCreatedAt() { return createdAt; }
}
