package com.couchbase.admin.users.bulkGroup.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.query.QueryResult;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service to create and manage FHIR Group resources.
 * Stores groups in fhir.Resources.General collection as proper FHIR resources.
 */
@Service
public class GroupAdminService {

    private static final Logger logger = LoggerFactory.getLogger(GroupAdminService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Resources";
    private static final String COLLECTION_NAME = "General";
    private static final int MAX_GROUP_MEMBERS = 10000;

    // Custom extension URLs
    private static final String EXT_CREATION_FILTER = "http://couchbase.fhir.com/StructureDefinition/creation-filter";
    private static final String EXT_CREATED_BY = "http://couchbase.fhir.com/StructureDefinition/created-by";
    private static final String EXT_LAST_REFRESHED = "http://couchbase.fhir.com/StructureDefinition/last-refreshed";
    private static final String EXT_RESOURCE_TYPE = "http://couchbase.fhir.com/StructureDefinition/member-resource-type";

    private final ConnectionService connectionService;
    private final FilterPreviewService filterPreviewService;
    private final FhirContext fhirContext;
    private final IParser jsonParser;

    public GroupAdminService(ConnectionService connectionService,
                             FilterPreviewService filterPreviewService,
                             FhirContext fhirContext) {
        this.connectionService = connectionService;
        this.filterPreviewService = filterPreviewService;
        this.fhirContext = fhirContext;
        this.jsonParser = fhirContext.newJsonParser();
        logger.info("✅ GroupAdminService initialized");
    }

    /**
     * Create a new FHIR Group from a filter
     */
    public Group createGroupFromFilter(String name, String resourceType, String filter, String createdBy) {
        logger.info("➕ Creating group '{}' for {} with filter: {}", name, resourceType, filter);
        logger.debug("🔍 Parameters: name='{}', resourceType='{}', filter='{}', createdBy='{}'", 
            name, resourceType, filter, createdBy);

        // Get all matching members
        logger.debug("🔍 Fetching matching IDs...");
        List<String> memberIds = filterPreviewService.getAllMatchingIds(resourceType, filter, MAX_GROUP_MEMBERS);
        logger.debug("✅ Found {} matching IDs", memberIds.size());
        
        if (memberIds.isEmpty()) {
            logger.error("❌ Filter returned no results. Cannot create empty group.");
            throw new IllegalArgumentException("Filter returned no results. Cannot create empty group.");
        }

        if (memberIds.size() >= MAX_GROUP_MEMBERS) {
            logger.warn("⚠️  Filter returned {} results, limiting to {}", memberIds.size(), MAX_GROUP_MEMBERS);
        }

        // Build FHIR Group resource
        logger.debug("🔍 Building FHIR Group resource...");
        Group group = new Group();
        group.setId(UUID.randomUUID().toString());
        logger.debug("✅ Generated Group ID: {}", group.getId());
        
        // Meta
        Meta meta = new Meta();
        meta.setVersionId("1");
        meta.setLastUpdated(Date.from(Instant.now()));
        meta.addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-group");
        
        // Add created-by tag
        if (createdBy != null && !createdBy.isEmpty()) {
            Coding tag = new Coding();
            tag.setSystem("http://couchbase.fhir.com/fhir/custom-tags");
            tag.setCode("created-by");
            tag.setDisplay("user:" + createdBy);
            meta.addTag(tag);
        }
        group.setMeta(meta);

        // Identifier
        Identifier identifier = new Identifier();
        identifier.setSystem("http://couchbase.fhir.com/group-id");
        identifier.setValue(group.getId());
        group.addIdentifier(identifier);

        // Core properties
        group.setActive(true);
        group.setType(Group.GroupType.PERSON); // Default to person, can be overridden
        group.setActual(true); // This is an actual list of members, not a definition
        group.setName(name);
        group.setQuantity(memberIds.size());

        // Add members
        for (String memberId : memberIds) {
            Group.GroupMemberComponent member = new Group.GroupMemberComponent();
            Reference ref = new Reference(memberId);
            member.setEntity(ref);
            group.addMember(member);
        }

        // Extensions
        Extension filterExt = new Extension();
        filterExt.setUrl(EXT_CREATION_FILTER);
        filterExt.setValue(new StringType(resourceType + "?" + filter));
        group.addExtension(filterExt);

        Extension createdByExt = new Extension();
        createdByExt.setUrl(EXT_CREATED_BY);
        createdByExt.setValue(new StringType(createdBy != null ? createdBy : "anonymous"));
        group.addExtension(createdByExt);

        Extension lastRefreshedExt = new Extension();
        lastRefreshedExt.setUrl(EXT_LAST_REFRESHED);
        lastRefreshedExt.setValue(new DateTimeType(Date.from(Instant.now())));
        group.addExtension(lastRefreshedExt);

        Extension resourceTypeExt = new Extension();
        resourceTypeExt.setUrl(EXT_RESOURCE_TYPE);
        resourceTypeExt.setValue(new StringType(resourceType));
        group.addExtension(resourceTypeExt);

        // Store in Couchbase
        logger.debug("🔍 Storing Group in Couchbase...");
        storeGroup(group);
        logger.debug("✅ Group stored successfully");

        logger.info("✅ Group created: {} with {} members", group.getId(), memberIds.size());
        return group;
    }

    /**
     * Get a Group by ID
     */
    public Optional<Group> getGroupById(String id) {
        try {
            Cluster cluster = connectionService.getConnection("default");
            Collection collection = cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
            
            String docKey = "Group::" + id;
            String json = collection.get(docKey).contentAs(String.class);
            Group group = jsonParser.parseResource(Group.class, json);
            
            return Optional.of(group);
        } catch (Exception e) {
            logger.debug("Group not found: {}", id);
            return Optional.empty();
        }
    }

    /**
     * Get all groups
     */
    public List<Group> getAllGroups() {
        try {
            Cluster cluster = connectionService.getConnection("default");
            String query = String.format(
                    "SELECT META(g).id as docId, g.* FROM `%s`.`%s`.`%s` g " +
                    "WHERE g.resourceType = 'Group' " +
                    "ORDER BY g.meta.lastUpdated DESC",
                    BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME);

            QueryResult result = cluster.query(query);
            List<Group> groups = new ArrayList<>();

            for (var row : result.rowsAsObject()) {
                try {
                    String json = row.toString();
                    Group group = jsonParser.parseResource(Group.class, json);
                    groups.add(group);
                } catch (Exception e) {
                    logger.warn("Failed to parse group: {}", e.getMessage());
                }
            }

            return groups;
        } catch (Exception e) {
            logger.error("Error fetching groups", e);
            return new ArrayList<>();
        }
    }

    /**
     * Refresh a group by re-running its filter
     */
    public Group refreshGroup(String id) {
        logger.info("🔄 Refreshing group: {}", id);

        Group existingGroup = getGroupById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + id));

        // Extract filter from extension
        Extension filterExt = existingGroup.getExtensionByUrl(EXT_CREATION_FILTER);
        if (filterExt == null || filterExt.getValue() == null) {
            throw new IllegalStateException("Group does not have a creation filter - cannot refresh");
        }

        String filterValue = filterExt.getValue().primitiveValue();
        // Format: "Patient?family=Smith&birthdate=ge1987"
        String[] parts = filterValue.split("\\?", 2);
        String resourceType = parts[0];
        String filter = parts.length > 1 ? parts[1] : "";

        // Get new member list
        List<String> newMemberIds = filterPreviewService.getAllMatchingIds(resourceType, filter, MAX_GROUP_MEMBERS);

        // Update members
        existingGroup.getMember().clear();
        for (String memberId : newMemberIds) {
            Group.GroupMemberComponent member = new Group.GroupMemberComponent();
            Reference ref = new Reference(memberId);
            member.setEntity(ref);
            existingGroup.addMember(member);
        }

        // Update quantity
        existingGroup.setQuantity(newMemberIds.size());

        // Update meta
        existingGroup.getMeta().setVersionId(String.valueOf(
                Integer.parseInt(existingGroup.getMeta().getVersionId()) + 1));
        existingGroup.getMeta().setLastUpdated(Date.from(Instant.now()));

        // Update last-refreshed extension
        Extension lastRefreshedExt = existingGroup.getExtensionByUrl(EXT_LAST_REFRESHED);
        if (lastRefreshedExt != null) {
            lastRefreshedExt.setValue(new DateTimeType(Date.from(Instant.now())));
        } else {
            Extension newExt = new Extension();
            newExt.setUrl(EXT_LAST_REFRESHED);
            newExt.setValue(new DateTimeType(Date.from(Instant.now())));
            existingGroup.addExtension(newExt);
        }

        // Store updated group
        storeGroup(existingGroup);

        logger.info("✅ Group refreshed: {} now has {} members", id, newMemberIds.size());
        return existingGroup;
    }

    /**
     * Remove a specific member from a group
     */
    public Group removeMember(String groupId, String memberReference) {
        logger.info("➖ Removing member {} from group {}", memberReference, groupId);

        Group group = getGroupById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

        boolean removed = group.getMember().removeIf(m -> 
                m.getEntity().getReference().equals(memberReference));

        if (!removed) {
            throw new IllegalArgumentException("Member not found in group: " + memberReference);
        }

        // Update quantity
        group.setQuantity(group.getMember().size());

        // Update meta
        group.getMeta().setVersionId(String.valueOf(
                Integer.parseInt(group.getMeta().getVersionId()) + 1));
        group.getMeta().setLastUpdated(Date.from(Instant.now()));

        // Store updated group
        storeGroup(group);

        logger.info("✅ Member removed from group {}", groupId);
        return group;
    }

    /**
     * Delete a group
     */
    public void deleteGroup(String id) {
        logger.info("🗑️  Deleting group: {}", id);

        if (getGroupById(id).isEmpty()) {
            throw new IllegalArgumentException("Group not found: " + id);
        }

        try {
            Cluster cluster = connectionService.getConnection("default");
            Collection collection = cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
            
            String docKey = "Group::" + id;
            collection.remove(docKey);

            logger.info("✅ Group deleted: {}", id);
        } catch (Exception e) {
            logger.error("Error deleting group", e);
            throw new RuntimeException("Failed to delete group: " + e.getMessage(), e);
        }
    }

    /**
     * Store a Group resource in Couchbase
     */
    private void storeGroup(Group group) {
        try {
            Cluster cluster = connectionService.getConnection("default");
            Collection collection = cluster.bucket(BUCKET_NAME).scope(SCOPE_NAME).collection(COLLECTION_NAME);
            
            String docKey = "Group::" + group.getId();
            String json = jsonParser.encodeResourceToString(group);

            collection.upsert(docKey, json);
            logger.debug("Group stored: {}", docKey);
        } catch (Exception e) {
            logger.error("Error storing group", e);
            throw new RuntimeException("Failed to store group: " + e.getMessage(), e);
        }
    }

    /**
     * Extract filter info from a Group for display
     */
    public static class GroupSummary {
        private final String id;
        private final String name;
        private final String filter;
        private final String resourceType;
        private final int memberCount;
        private final String createdBy;
        private final Date lastUpdated;
        private final Date lastRefreshed;

        public GroupSummary(Group group) {
            this.id = group.getId();
            this.name = group.getName();
            this.memberCount = group.getQuantity();
            this.lastUpdated = group.getMeta().getLastUpdated();

            // Extract from extensions
            Extension filterExt = group.getExtensionByUrl(EXT_CREATION_FILTER);
            this.filter = filterExt != null ? filterExt.getValue().primitiveValue() : null;

            Extension resourceTypeExt = group.getExtensionByUrl(EXT_RESOURCE_TYPE);
            this.resourceType = resourceTypeExt != null ? resourceTypeExt.getValue().primitiveValue() : "Unknown";

            Extension createdByExt = group.getExtensionByUrl(EXT_CREATED_BY);
            this.createdBy = createdByExt != null ? createdByExt.getValue().primitiveValue() : "Unknown";

            Extension lastRefreshedExt = group.getExtensionByUrl(EXT_LAST_REFRESHED);
            this.lastRefreshed = lastRefreshedExt != null ? 
                    ((DateTimeType) lastRefreshedExt.getValue()).getValue() : null;
        }

        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getFilter() { return filter; }
        public String getResourceType() { return resourceType; }
        public int getMemberCount() { return memberCount; }
        public String getCreatedBy() { return createdBy; }
        public Date getLastUpdated() { return lastUpdated; }
        public Date getLastRefreshed() { return lastRefreshed; }
    }
}

