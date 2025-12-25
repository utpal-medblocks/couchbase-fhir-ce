package com.couchbase.admin.users.bulkGroup.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.users.bulkGroup.model.BulkGroup;
import com.couchbase.client.java.Cluster;
import com.couchbase.admin.fhirResource.service.FhirDocumentAdminService;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BulkGroupService {

    private static final Logger logger = LoggerFactory.getLogger(BulkGroupService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "bulk_groups";

    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private FhirDocumentAdminService fhirDocumentAdminService;

    private Collection getCollection() {
        Cluster cluster = connectionService.getConnection("default");
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }

    public BulkGroup createBulkGroup(BulkGroup g) {
        logger.info("➕ Creating bulk group: {}", g.getId());
        if (g.getId() == null || g.getId().isEmpty()) {
            g.setId(java.util.UUID.randomUUID().toString());
        }
        
        if (getBulkGroupById(g.getId()).isPresent()) {
            throw new IllegalArgumentException("BulkGroup with ID '" + g.getId() + "' already exists");
        }

        g.setCreatedAt(Instant.now());
        if (g.getPatientIds() == null) g.setPatientIds(new ArrayList<>());

        Collection collection = getCollection();
        collection.insert(g.getId(), g);
        logger.info("✅ Bulk group created: {}", g.getId());
        return g;
    }

    public Optional<BulkGroup> getBulkGroupById(String id) {
        try {
            Collection collection = getCollection();
            BulkGroup g = collection.get(id).contentAs(BulkGroup.class);
            g.setId(id);
            return Optional.ofNullable(g);
        } catch (Exception e) {
            logger.debug("BulkGroup not found: {}", id);
            return Optional.empty();
        }
    }

    public List<BulkGroup> getAllBulkGroups() {
        try {
            Cluster cluster = connectionService.getConnection("default");
                String query = "SELECT META(b).id AS id, b.* FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "` b ORDER BY b.createdAt DESC";
            QueryResult result = cluster.query(query);
            // Map rows to BulkGroup objects
            List<BulkGroup> groups = result.rowsAs(BulkGroup.class);
            return groups;
        } catch (Exception e) {
            logger.error("Error fetching bulk groups", e);
            return new ArrayList<>();
        }
    }

    /**
     * Resolve display names for a list of patient IDs by fetching the stored FHIR Patient document.
     * Returns a map patientId -> displayName. Missing names will not be included.
     */
    public java.util.Map<String, String> getPatientNamesForIds(java.util.List<String> ids) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) return map;

        try {
            Cluster cluster = connectionService.getConnection("default");
            if (cluster == null) return map;

            // Build IN-list for p.id values so we match documents regardless of document key format
            StringBuilder idList = new StringBuilder("[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) idList.append(",");
                idList.append('"').append(ids.get(i).replace("\"", "\\\"")).append('"');
            }
            idList.append("]");

            String query = "SELECT META(p).id AS docId, p.name AS name, p.title AS title, p.id AS resourceId FROM `" + BUCKET_NAME + "`.`Resources`.`Patient` p WHERE p.id IN " + idList.toString();
            logger.info(String.format("============ query = %s", query));
            QueryResult result = cluster.query(query);
            for (var row : result.rowsAsObject()) {
                try {
                    String docId = row.getString("docId");
                    String pid = docId != null && docId.startsWith("Patient::") ? docId.substring(9) : docId;

                    String display = null;
                    if (row.containsKey("name") && row.getArray("name") != null && row.getArray("name").size() > 0) {
                        var n = row.getArray("name").getObject(0);
                        if (n != null) {
                            if (n.containsKey("text") && n.getString("text") != null && !n.getString("text").isBlank()) {
                                display = n.getString("text");
                            } else {
                                String given = "";
                                if (n.containsKey("given") && n.getArray("given") != null) {
                                    var givenArr = n.getArray("given");
                                    StringBuilder gsb = new StringBuilder();
                                    for (int i = 0; i < givenArr.size(); i++) {
                                        if (i > 0) gsb.append(" ");
                                        gsb.append(givenArr.get(i).toString());
                                    }
                                    given = gsb.toString().trim();
                                }
                                String family = n.containsKey("family") ? n.getString("family") : "";
                                String combined = (given + " " + (family != null ? family : "")).trim();
                                if (!combined.isEmpty()) display = combined;
                            }
                        }
                    }

                    if ((display == null || display.isBlank()) && row.containsKey("title")) {
                        display = row.getString("title");
                    }

                    if ((display == null || display.isBlank()) && row.containsKey("resourceId")) {
                        display = row.getString("resourceId");
                    }

                    if (display == null || display.isBlank()) display = pid;

                    if (pid != null) map.put(pid, display);
                } catch (Exception e) {
                    logger.debug("Failed to process patient row: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.debug("Failed to fetch patient names in batch: {}", e.getMessage());
        }

        return map;
    }

    private String extractPatientDisplayName(Object doc) {
        try {
            if (!(doc instanceof java.util.Map)) return null;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) doc;

            Object nameObj = m.get("name");
            if (nameObj instanceof java.util.List) {
                java.util.List<?> names = (java.util.List<?>) nameObj;
                if (!names.isEmpty() && names.get(0) instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> n = (java.util.Map<String, Object>) names.get(0);
                    Object text = n.get("text");
                    if (text instanceof String && !((String) text).isBlank()) return (String) text;

                    String given = "";
                    if (n.get("given") instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        java.util.List<Object> givenList = (java.util.List<Object>) n.get("given");
                        given = String.join(" ", givenList.stream().map(Object::toString).toArray(String[]::new)).trim();
                    }
                    String family = n.getOrDefault("family", "").toString();
                    String combined = (given + " " + family).trim();
                    if (!combined.isEmpty()) return combined;
                }
            }

            // fallback: use id or title
            if (m.containsKey("id")) return m.get("id").toString();
            if (m.containsKey("title")) return m.get("title").toString();
            return null;
        } catch (Exception e) {
            logger.debug("Failed to extract patient display name: {}", e.getMessage());
            return null;
        }
    }

    public BulkGroup updateBulkGroup(String id, BulkGroup updated) {
        logger.info("📝 Updating bulk group: {}", id);
        BulkGroup existing = getBulkGroupById(id).orElseThrow(() -> new IllegalArgumentException("BulkGroup not found: " + id));

        if (updated.getPatientIds() != null) {
            existing.setPatientIds(updated.getPatientIds());
        }

        Collection collection = getCollection();
        collection.replace(id, existing);
        logger.info("✅ Bulk group updated: {}", id);
        return existing;
    }

    public void deleteBulkGroup(String id) {
        logger.info("🗑️ Deleting bulk group: {}", id);
        if (getBulkGroupById(id).isEmpty()) {
            throw new IllegalArgumentException("BulkGroup not found: " + id);
        }
        Collection collection = getCollection();
        collection.remove(id);
        logger.info("✅ Bulk group deleted: {}", id);
    }
}
