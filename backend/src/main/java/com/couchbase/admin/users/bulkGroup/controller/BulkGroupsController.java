package com.couchbase.admin.users.bulkGroup.controller;

import com.couchbase.admin.users.bulkGroup.dto.BulkGroupResponse;
import com.couchbase.admin.users.bulkGroup.model.BulkGroup;
import com.couchbase.admin.users.bulkGroup.service.BulkGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/bulk-groups")
public class BulkGroupsController {

    private static final Logger logger = LoggerFactory.getLogger(BulkGroupsController.class);

    @Autowired
    private BulkGroupService bulkGroupService;

    @GetMapping
    public ResponseEntity<List<BulkGroupResponse>> getAll() {
        logger.info("📋 Fetching all bulk groups");
        List<BulkGroup> groups = bulkGroupService.getAllBulkGroups();
        List<BulkGroupResponse> dto = groups.stream().map(BulkGroupResponse::from).toList();
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        logger.info("🔍 Fetching bulk group: {}", id);
        return bulkGroupService.getBulkGroupById(id)
                .map(g -> ResponseEntity.ok(BulkGroupResponse.from(g)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BulkGroup group) {
        try {
            logger.info("➕ Creating bulk group: {}", group.getId());
            BulkGroup created = bulkGroupService.createBulkGroup(group);
            return ResponseEntity.status(HttpStatus.CREATED).body(BulkGroupResponse.from(created));
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error creating bulk group: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error creating bulk group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create bulk group"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody BulkGroup updated) {
        try {
            logger.info("📝 Updating bulk group: {}", id);
            BulkGroup g = bulkGroupService.updateBulkGroup(id, updated);
            return ResponseEntity.ok(BulkGroupResponse.from(g));
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error updating bulk group: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error updating bulk group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to update bulk group"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            logger.info("🗑️ Deleting bulk group: {}", id);
            bulkGroupService.deleteBulkGroup(id);
            return ResponseEntity.ok(Map.of("message", "Bulk group deleted successfully"));
        } catch (IllegalArgumentException e) {
            logger.error("❌ Error deleting bulk group: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Unexpected error deleting bulk group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to delete bulk group"));
        }
    }
}
