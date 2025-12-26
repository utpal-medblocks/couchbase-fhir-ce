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

    /** Optional human-friendly name for the group */
    private String name;

    /** Optional description for the group */
    private String description;

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

    public BulkGroup(String id, String name, String description, List<String> patientIds) {
        this(id, patientIds);
        this.name = name;
        this.description = description;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", patientIds=" + patientIds +
                '}';
    }
}
