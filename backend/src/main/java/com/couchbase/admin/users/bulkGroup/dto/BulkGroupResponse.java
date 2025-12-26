package com.couchbase.admin.users.bulkGroup.dto;

import com.couchbase.admin.users.bulkGroup.model.BulkGroup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * DTO for exposing BulkGroup through REST APIs
 */
public class BulkGroupResponse {
    private String id;
    private List<String> patientIds;
    private String name;
    private String description;
    private Instant createdAt;
    private Map<String, String> patientNames;

    public static BulkGroupResponse from(BulkGroup g) {
        if (g == null) return null;
        BulkGroupResponse r = new BulkGroupResponse();
        r.id = g.getId();
        r.patientIds = g.getPatientIds();
        r.name = g.getName();
        r.description = g.getDescription();
        r.createdAt = g.getCreatedAt();
        r.patientNames = new HashMap<>();
        return r;
    }

    public String getId() { return id; }
    public List<String> getPatientIds() { return patientIds; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<String, String> getPatientNames() { return patientNames; }
    public void setPatientNames(Map<String, String> patientNames) { this.patientNames = patientNames; }
}
