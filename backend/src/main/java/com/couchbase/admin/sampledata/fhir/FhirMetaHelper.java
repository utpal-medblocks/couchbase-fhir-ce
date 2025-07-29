package com.couchbase.admin.sampledata.fhir;

import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CanonicalType;
import java.util.ArrayList;
import java.util.Collections;
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
     * Apply audit tags to meta (only created-by tag for concise storage)
     */
    private static void applyAuditTags(Meta meta, String userId, String operation) {
        // Normalize userId
        String normalizedUserId = userId != null ? userId : "user:anonymous";
        if (!normalizedUserId.startsWith("user:") && !normalizedUserId.startsWith("system:")) {
            normalizedUserId = "user:" + normalizedUserId;
        }
        
        // Add only created-by tag (operation tag removed to save disk space) 
        // Don't clear existing tags, just set new ones to avoid UnsupportedOperationException
        Coding createdByTag = new Coding()
                .setSystem(AUDIT_TAG_SYSTEM)
                .setCode(CREATED_BY_CODE)
                .setDisplay(normalizedUserId);
        
        // Create new list instead of clearing existing one
        List<Coding> newTags = new ArrayList<>();
        newTags.add(createdByTag);
        meta.setTag(newTags);
    }
} 