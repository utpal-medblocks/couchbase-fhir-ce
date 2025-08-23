package com.couchbase.common.fhir;

import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CanonicalType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Centralized helper for applying FHIR meta tags consistently across all services.
 * This ensures uniform audit information across the application.
 */
public class FhirMetaHelper {

    // Constants for audit tags
    private static final String AUDIT_TAG_SYSTEM = "http://couchbase.fhir.com/fhir/custom-tags";
    private static final String CREATED_BY_CODE = "created-by";
    private static final String UPDATED_BY_CODE = "updated-by";
    private static final String DELETED_BY_CODE = "deleted-by";
    
    /**
     * Apply complete meta information to a FHIR resource (most comprehensive method)
     */
    public static void applyCompleteMeta(Resource resource, String userId, String operation, 
                                        Date lastUpdated, String versionId, List<String> profiles) {
        if (resource == null) {
            return;
        }
        
        Meta meta = getOrCreateMeta(resource);
        
        // Set standard meta fields
        if (lastUpdated != null) {
            meta.setLastUpdated(lastUpdated);
        } else {
            meta.setLastUpdated(new Date());
        }
        
        if (versionId != null) {
            meta.setVersionId(versionId);
        }
        
        // Set profiles if provided
        if (profiles != null && !profiles.isEmpty()) {
            List<CanonicalType> canonicalProfiles = new ArrayList<>();
            for (String profile : profiles) {
                canonicalProfiles.add(new CanonicalType(profile));
            }
            meta.setProfile(canonicalProfiles);
        }
        
        // Apply audit tags
        applyAuditTags(meta, userId, operation);
    }
    
    /**
     * Apply basic meta information with defaults (simplified method)
     */
    public static void applyMeta(Resource resource, Date lastUpdated, String versionId, 
                                List<String> profiles, String createdBy) {
        if (resource == null) {
            return;
        }
        
        // Extract operation from createdBy if it contains operation info
        String operation = "CREATE"; // default
        String userId = createdBy != null ? createdBy : "user:anonymous";
        
        applyCompleteMeta(resource, userId, operation, lastUpdated, versionId, profiles);
    }
    
    /**
     * Apply minimal meta information (for simple cases)
     */
    public static void applyMinimalMeta(Resource resource, String userId, String operation) {
        applyCompleteMeta(resource, userId, operation, new Date(), "1", null);
    }
    
    /**
     * Convenience method for CREATE operations
     */
    public static void applyCreateMeta(Resource resource, String userId) {
        applyMinimalMeta(resource, userId, "CREATE");
    }
    
    /**
     * Convenience method for UPDATE operations
     */
    public static void applyUpdateMeta(Resource resource, String userId, String versionId) {
        applyCompleteMeta(resource, userId, "UPDATE", new Date(), versionId, extractExistingProfiles(resource));
    }
    
    /**
     * Convenience method for DELETE operations (soft delete with audit)
     */
    public static void applyDeleteMeta(Resource resource, String userId) {
        applyCompleteMeta(resource, userId, "DELETE", new Date(), 
            incrementVersion(resource.getMeta()), extractExistingProfiles(resource));
    }
    
    /**
     * Helper method to increment version ID
     */
    private static String incrementVersion(Meta meta) {
        if (meta == null || meta.getVersionId() == null) {
            return "1";
        }
        try {
            int currentVersion = Integer.parseInt(meta.getVersionId());
            return String.valueOf(currentVersion + 1);
        } catch (NumberFormatException e) {
            return "1"; // Fallback if version is not numeric
        }
    }
    
    /**
     * Apply audit-only meta (preserves existing meta, adds audit tags)
     */
    public static void applyAuditOnly(Resource resource, String userId, String operation) {
        if (resource == null) {
            return;
        }
        
        Meta meta = getOrCreateMeta(resource);
        
        // Only update lastUpdated if not already set
        if (meta.getLastUpdated() == null) {
            meta.setLastUpdated(new Date());
        }
        
        // Only set versionId if not already set
        if (meta.getVersionId() == null) {
            meta.setVersionId("1");
        }
        
        // Apply audit tags
        applyAuditTags(meta, userId, operation);
    }
    
    /**
     * Extract existing profiles from a resource
     */
    public static List<String> extractExistingProfiles(Resource resource) {
        if (resource == null || resource.getMeta() == null || !resource.getMeta().hasProfile()) {
            return null;
        }
        
        List<String> profiles = new ArrayList<>();
        for (CanonicalType ct : resource.getMeta().getProfile()) {
            if (ct.getValue() != null) {
                profiles.add(ct.getValue());
            }
        }
        return profiles.isEmpty() ? null : profiles;
    }
    
    /**
     * Get or create Meta object for a resource
     */
    private static Meta getOrCreateMeta(Resource resource) {
        Meta meta = resource.getMeta();
        if (meta == null) {
            meta = new Meta();
            resource.setMeta(meta);
        }
        return meta;
    }
    
    /**
     * Apply audit tags to meta based on operation type
     */
    private static void applyAuditTags(Meta meta, String userId, String operation) {
        // Normalize userId
        String normalizedUserId = userId != null ? userId : "user:anonymous";
        if (!normalizedUserId.startsWith("user:") && !normalizedUserId.startsWith("system:")) {
            normalizedUserId = "user:" + normalizedUserId;
        }
        
        // Determine audit tag code based on operation
        String auditCode;
        switch (operation.toUpperCase()) {
            case "CREATE":
                auditCode = CREATED_BY_CODE;
                break;
            case "UPDATE":
                auditCode = UPDATED_BY_CODE;
                break;
            case "DELETE":
                auditCode = DELETED_BY_CODE;
                break;
            default:
                auditCode = CREATED_BY_CODE; // Default fallback
        }
        
        // Create audit tag
        Coding auditTag = new Coding()
                .setSystem(AUDIT_TAG_SYSTEM)
                .setCode(auditCode)
                .setDisplay(normalizedUserId);
        
        // Handle existing tags - preserve non-audit tags, replace audit tags
        List<Coding> existingTags = meta.getTag();
        List<Coding> newTags = new ArrayList<>();
        
        // Keep existing non-audit tags
        if (existingTags != null) {
            for (Coding tag : existingTags) {
                if (!AUDIT_TAG_SYSTEM.equals(tag.getSystem())) {
                    newTags.add(tag);
                }
            }
        }
        
        // Add the new audit tag
        newTags.add(auditTag);
        meta.setTag(newTags);
    }
    
    /**
     * Add ONLY audit tags to resource meta - does not touch versionId, lastUpdated, etc.
     * This is for separation of concerns - audit service only handles audit info
     */
    public static void addAuditTags(Resource resource, String userId, String operation) {
        if (resource == null) {
            return;
        }
        
        Meta meta = getOrCreateMeta(resource);
        
        // Determine audit code based on operation
        String auditCode;
        switch (operation.toUpperCase()) {
            case "CREATE":
                auditCode = CREATED_BY_CODE;
                break;
            case "UPDATE":
                auditCode = UPDATED_BY_CODE;
                break;
            case "DELETE":
                auditCode = DELETED_BY_CODE;
                break;
            default:
                auditCode = CREATED_BY_CODE; // Default fallback
        }
        
        // Create audit tag
        String normalizedUserId = (userId != null && !userId.trim().isEmpty()) ? userId : "system";
        Coding auditTag = new Coding()
                .setSystem(AUDIT_TAG_SYSTEM)
                .setCode(auditCode)
                .setDisplay(normalizedUserId);
        
        // Handle existing tags - preserve non-audit tags, replace audit tags
        List<Coding> existingTags = meta.getTag();
        List<Coding> newTags = new ArrayList<>();
        
        // Keep existing non-audit tags
        if (existingTags != null) {
            for (Coding tag : existingTags) {
                if (!AUDIT_TAG_SYSTEM.equals(tag.getSystem())) {
                    newTags.add(tag);
                }
            }
        }
        
        // Add the new audit tag
        newTags.add(auditTag);
        meta.setTag(newTags);
    }
}
