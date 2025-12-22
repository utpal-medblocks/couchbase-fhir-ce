package com.couchbase.admin.users.bulkGroup.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.users.bulkGroup.model.BulkGroup;
import com.couchbase.client.java.Cluster;
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

    private Collection getCollection() {
        Cluster cluster = connectionService.getConnection("default");
        return cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
    }

    public BulkGroup createBulkGroup(BulkGroup g) {
        logger.info("➕ Creating bulk group: {}", g.getId());
        if (g.getId() == null || g.getId().isEmpty()) {
            throw new IllegalArgumentException("BulkGroup ID is required");
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
            return Optional.ofNullable(g);
        } catch (Exception e) {
            logger.debug("BulkGroup not found: {}", id);
            return Optional.empty();
        }
    }

    public List<BulkGroup> getAllBulkGroups() {
        try {
            Cluster cluster = connectionService.getConnection("default");
            String query = "SELECT b.* FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "` b ORDER BY b.createdAt DESC";
            QueryResult result = cluster.query(query);
            return result.rowsAs(BulkGroup.class);
        } catch (Exception e) {
            logger.error("Error fetching bulk groups", e);
            return new ArrayList<>();
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
