package com.couchbase.admin.users.bulkGroup.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkGroup {

    /** Document ID (used as Couchbase document key) */
    private String id;

    /** List of patient IDs that are members of this bulk group */
    private List<String> patientIds;

    /** Optional creation timestamp */
    private Instant createdAt;

    public BulkGroup() {
        this.patientIds = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    public BulkGroup(String id, List<String> patientIds) {
        this();
        this.id = id;
        this.patientIds = patientIds != null ? patientIds : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getPatientIds() {
        return patientIds;
    }

    public void setPatientIds(List<String> patientIds) {
        this.patientIds = patientIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "BulkGroup{" +
                "id='" + id + '\'' +
                ", patientIds=" + patientIds +
                '}';
    }
}
