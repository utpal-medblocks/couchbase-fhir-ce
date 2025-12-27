package com.couchbase.admin.users.bulkGroup.controller;

import com.couchbase.admin.users.bulkGroup.service.FilterPreviewService;
import com.couchbase.admin.users.bulkGroup.service.GroupAdminService;
import org.hl7.fhir.r4.model.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Admin API for managing FHIR Group resources.
 * Provides CRUD operations that are not exposed via public FHIR API.
 */
@RestController
@RequestMapping("/api/admin/groups")
public class GroupAdminController {

    private static final Logger logger = LoggerFactory.getLogger(GroupAdminController.class);

    private final GroupAdminService groupAdminService;
    private final FilterPreviewService filterPreviewService;

    public GroupAdminController(GroupAdminService groupAdminService, 
                                FilterPreviewService filterPreviewService) {
        this.groupAdminService = groupAdminService;
        this.filterPreviewService = filterPreviewService;
        logger.info("✅ GroupAdminController initialized");
    }

    /**
     * Preview a filter before creating a group
     * POST /api/admin/groups/preview
     * Body: { "resourceType": "Patient", "filter": "family=Smith&birthdate=ge1987" }
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewFilter(@RequestBody Map<String, String> request) {
        try {
            String resourceType = request.get("resourceType");
            String filter = request.get("filter");

            if (resourceType == null || resourceType.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "resourceType is required"));
            }

            logger.info("🔍 Preview request for {}: {}", resourceType, filter);

            FilterPreviewService.FilterPreviewResult result = 
                    filterPreviewService.executeFilterPreview(resourceType, filter != null ? filter : "");

            return ResponseEntity.ok(Map.of(
                    "totalCount", result.getTotalCount(),
                    "sampleResources", result.getSampleResources(),
                    "resourceType", result.getResourceType(),
                    "filter", result.getFilter()
            ));

        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid preview request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error executing preview", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to execute preview: " + e.getMessage()));
        }
    }

    /**
     * Create a new group from a filter
     * POST /api/admin/groups
     * Body: { "name": "My Group", "resourceType": "Patient", "filter": "family=Smith", "createdBy": "admin" }
     */
    @PostMapping
    public ResponseEntity<?> createGroup(@RequestBody Map<String, String> request) {
        try {
            logger.debug("📥 Received create group request: {}", request);
            
            String name = request.get("name");
            String resourceType = request.get("resourceType");
            String filter = request.get("filter");
            String createdBy = request.getOrDefault("createdBy", "anonymous");

            if (name == null || name.isEmpty()) {
                logger.error("❌ Missing required field: name");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "name is required"));
            }
            if (resourceType == null || resourceType.isEmpty()) {
                logger.error("❌ Missing required field: resourceType");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "resourceType is required"));
            }

            logger.info("➕ Create group request: '{}' for {} (filter: {})", name, resourceType, filter);

            Group group = groupAdminService.createGroupFromFilter(
                    name, resourceType, filter != null ? filter : "", createdBy);

            logger.debug("✅ Group created, building response...");
            GroupAdminService.GroupSummary summary = new GroupAdminService.GroupSummary(group);

            Map<String, Object> response = Map.of(
                    "id", summary.getId(),
                    "name", summary.getName(),
                    "filter", summary.getFilter(),
                    "resourceType", summary.getResourceType(),
                    "memberCount", summary.getMemberCount(),
                    "createdBy", summary.getCreatedBy(),
                    "lastUpdated", summary.getLastUpdated().toString(),
                    "lastRefreshed", summary.getLastRefreshed() != null ? 
                            summary.getLastRefreshed().toString() : null
            );
            
            logger.info("✅ Returning 201 Created with group: {}", summary.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid create request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error creating group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create group: " + e.getMessage()));
        }
    }

    /**
     * Get all groups
     * GET /api/admin/groups
     */
    @GetMapping
    public ResponseEntity<?> getAllGroups() {
        try {
            logger.info("📋 Fetching all groups (summary mode)");

            List<GroupAdminService.GroupSummaryRow> summaries = groupAdminService.getAllGroupSummaries();
            return ResponseEntity.ok(Map.of("groups", summaries));

        } catch (Exception e) {
            logger.error("❌ Error fetching groups", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch groups: " + e.getMessage()));
        }
    }

    /**
     * Get a specific group by ID
     * GET /api/admin/groups/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getGroupById(@PathVariable String id) {
        try {
            logger.info("🔍 Fetching group: {}", id);

            return groupAdminService.getGroupById(id)
                    .map(group -> {
                        GroupAdminService.GroupSummary summary = new GroupAdminService.GroupSummary(group);
                        return ResponseEntity.ok(Map.of(
                                "id", (Object) summary.getId(),
                                "name", summary.getName(),
                                "filter", summary.getFilter() != null ? summary.getFilter() : "",
                                "resourceType", summary.getResourceType(),
                                "memberCount", summary.getMemberCount(),
                                "createdBy", summary.getCreatedBy(),
                                "lastUpdated", summary.getLastUpdated().toString(),
                                "lastRefreshed", summary.getLastRefreshed() != null ? 
                                        summary.getLastRefreshed().toString() : "",
                                "members", group.getMember().stream()
                                        .map(m -> m.getEntity().getReference())
                                        .collect(Collectors.toList())
                        ));
                    })
                    .orElse(ResponseEntity.notFound().build());

        } catch (Exception e) {
            logger.error("❌ Error fetching group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch group: " + e.getMessage()));
        }
    }

    /**
     * Update an existing group (Edit - re-run filter with same ID)
     * PUT /api/admin/groups/{id}
     * Body: { "name": "...", "resourceType": "...", "filter": "..." }
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(@PathVariable String id, @RequestBody Map<String, String> request) {
        try {
            logger.info("✏️  Update request for group: {}", id);
            logger.debug("📥 Update request body: {}", request);
            
            String name = request.get("name");
            String resourceType = request.get("resourceType");
            String filter = request.get("filter");
            
            if (name == null || name.isEmpty()) {
                logger.error("❌ Missing required field: name");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "name is required"));
            }
            if (resourceType == null || resourceType.isEmpty()) {
                logger.error("❌ Missing required field: resourceType");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "resourceType is required"));
            }
            
            // Check if group exists
            Optional<Group> existingOpt = groupAdminService.getGroupById(id);
            if (existingOpt.isEmpty()) {
                logger.error("❌ Group not found: {}", id);
                return ResponseEntity.notFound().build();
            }
            
            Group existing = existingOpt.get();
            
            // Get createdBy from existing group
            String createdBy = existing.getExtension().stream()
                    .filter(e -> "http://couchbase.fhir.com/StructureDefinition/created-by".equals(e.getUrl()))
                    .map(e -> ((org.hl7.fhir.r4.model.StringType) e.getValue()).getValue())
                    .findFirst()
                    .orElse("anonymous");
            
            // Re-create group with same ID (UPSERT)
            logger.debug("🔍 Calling updateGroupFromFilter with id: {}", id);
            Group updatedGroup = groupAdminService.updateGroupFromFilter(id, name, resourceType, filter, createdBy);
            GroupAdminService.GroupSummary summary = new GroupAdminService.GroupSummary(updatedGroup);
            
            Map<String, Object> response = Map.of(
                    "id", summary.getId(),
                    "name", summary.getName(),
                    "filter", summary.getFilter(),
                    "resourceType", summary.getResourceType(),
                    "memberCount", summary.getMemberCount(),
                    "createdBy", summary.getCreatedBy(),
                    "lastUpdated", summary.getLastUpdated().toString(),
                    "lastRefreshed", summary.getLastRefreshed() != null ? 
                            summary.getLastRefreshed().toString() : ""
            );
            
            logger.info("✅ Group updated successfully: {}", id);
            logger.debug("📤 Returning response: {}", response);
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid update request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error updating group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update group: " + e.getMessage()));
        }
    }

    /**
     * Refresh a group (re-run its filter)
     * POST /api/admin/groups/{id}/refresh
     */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refreshGroup(@PathVariable String id) {
        try {
            logger.info("🔄 Refresh request for group: {}", id);

            Group group = groupAdminService.refreshGroup(id);
            GroupAdminService.GroupSummary summary = new GroupAdminService.GroupSummary(group);

            return ResponseEntity.ok(Map.of(
                    "id", summary.getId(),
                    "name", summary.getName(),
                    "memberCount", summary.getMemberCount(),
                    "lastRefreshed", summary.getLastRefreshed() != null ? 
                            summary.getLastRefreshed().toString() : "",
                    "message", "Group refreshed successfully"
            ));

        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.error("❌ Invalid refresh request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error refreshing group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to refresh group: " + e.getMessage()));
        }
    }

    /**
     * Remove a member from a group
     * DELETE /api/admin/groups/{id}/members/{memberReference}
     * Example: DELETE /api/admin/groups/abc123/members/Patient%2F456
     */
    @DeleteMapping("/{id}/members/{memberReference}")
    public ResponseEntity<?> removeMember(@PathVariable String id, 
                                          @PathVariable String memberReference) {
        try {
            // URL decode the member reference (e.g., "Patient%2F456" -> "Patient/456")
            String decodedReference = java.net.URLDecoder.decode(memberReference, "UTF-8");
            logger.info("➖ Remove member '{}' from group '{}'", decodedReference, id);

            Group group = groupAdminService.removeMember(id, decodedReference);

            return ResponseEntity.ok(Map.of(
                    "message", "Member removed successfully",
                    "groupId", group.getId(),
                    "newMemberCount", group.getQuantity()
            ));

        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid remove member request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error removing member", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to remove member: " + e.getMessage()));
        }
    }

    /**
     * Delete a group
     * DELETE /api/admin/groups/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable String id) {
        try {
            logger.info("🗑️  Delete request for group: {}", id);

            groupAdminService.deleteGroup(id);

            return ResponseEntity.ok(Map.of(
                    "message", "Group deleted successfully",
                    "id", id
            ));

        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid delete request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("❌ Error deleting group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete group: " + e.getMessage()));
        }
    }
}

