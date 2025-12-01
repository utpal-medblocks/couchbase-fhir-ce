package com.couchbase.common.fhir;

import com.couchbase.fhir.resources.service.FhirAuditService;
import com.couchbase.fhir.resources.service.MetaRequest;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CanonicalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralized Meta orchestrator for FHIR resources
 * Single entry point for all meta operations: versioning, timestamps, profiles, and audit tags
 * Works with FhirAuditService to get audit tags but handles all other meta concerns
 */
@Component
public class FhirMetaHelper {

    @Autowired
    private FhirAuditService auditService;
    
    /**
     * Main entry point: Apply meta information based on MetaRequest
     * This is the single orchestrator for all meta operations
     */
    public void applyMeta(Resource resource, MetaRequest request) {
        if (resource == null || request == null) {
            return;
        }
        
        Meta meta = getOrCreateMeta(resource);
        
        // 1. Set lastUpdated
        Date timestamp = request.lastUpdated != null ? request.lastUpdated : new Date();
        meta.setLastUpdated(timestamp);
        
        // 2. Handle versionId based on operation
        String versionId = determineVersionId(resource, request);
        if (versionId != null) {
            meta.setVersionId(versionId);
        }
        
        // 3. Handle profiles (merge with existing)
        if (request.profiles != null && !request.profiles.isEmpty()) {
            mergeProfiles(meta, request.profiles);
        }
        
        // 4. Get audit tag from audit service and replace existing audit tags
        String userId = request.userId != null ? request.userId : auditService.getCurrentUserId();
        Coding auditTag = auditService.newAuditTag(request.op, userId);
        replaceAuditTag(meta, auditTag);
    }
    
    /**
     * Convenience method for CREATE operations
     */
    public void applyCreateMeta(Resource resource, String userId, String versionId, List<String> profiles) {
        MetaRequest request = MetaRequest.forCreate(userId, versionId, profiles);
        applyMeta(resource, request);
    }
    
    /**
     * Convenience method for UPDATE operations
     */
    public void applyUpdateMeta(Resource resource, String userId, String versionId, List<String> profiles) {
        MetaRequest request = MetaRequest.forUpdate(userId, versionId, profiles);
        applyMeta(resource, request);
    }
    
    /**
     * Convenience method for DELETE operations
     */
    public void applyDeleteMeta(Resource resource, String userId) {
        MetaRequest request = MetaRequest.forDelete(userId);
        applyMeta(resource, request);
    }

    /**
     * Determine version ID based on operation and request
     */
    private String determineVersionId(Resource resource, MetaRequest request) {
        // If explicitly provided, use it
        if (request.versionId != null) {
            return request.versionId;
        }
        
        // Operation-specific defaults
        switch (request.op) {
            case CREATE:
                return "1"; // Always start at version 1 for new resources
                
            case UPDATE:
                // For updates, increment existing version or default to 2
                Meta updateMeta = resource.getMeta();
                if (updateMeta != null && updateMeta.getVersionId() != null) {
                    return incrementVersion(updateMeta.getVersionId());
                }
                return "2"; // If no existing version, assume this is version 2
                
            case DELETE:
                // For deletes, optionally bump version
                if (request.bumpVersionIfMissing) {
                    Meta deleteMeta = resource.getMeta();
                    if (deleteMeta != null && deleteMeta.getVersionId() != null) {
                        return incrementVersion(deleteMeta.getVersionId());
                    }
                    return "1"; // If no existing version, default to 1
                }
                return null; // Don't change version for delete
                
            default:
                return "1";
        }
    }
    
    /**
     * Helper method to increment version ID
     */
    private String incrementVersion(String versionId) {
        if (versionId == null) {
            return "1";
        }
        try {
            int currentVersion = Integer.parseInt(versionId);
            return String.valueOf(currentVersion + 1);
        } catch (NumberFormatException e) {
            return "1"; // Fallback if version is not numeric
        }
    }

    /**
     * Merge profiles with existing ones, avoiding duplicates
     */
    private void mergeProfiles(Meta meta, List<String> newProfiles) {
        Set<String> allProfiles = new HashSet<>();
        
        // Add existing profiles
        if (meta.hasProfile()) {
            for (CanonicalType existing : meta.getProfile()) {
                if (existing.getValue() != null) {
                    allProfiles.add(existing.getValue());
                }
            }
        }
        
        // Add new profiles
        allProfiles.addAll(newProfiles);
        
        // Set merged profiles
        List<CanonicalType> canonicalProfiles = new ArrayList<>();
        for (String profile : allProfiles) {
            canonicalProfiles.add(new CanonicalType(profile));
        }
        meta.setProfile(canonicalProfiles);
    }

    /**
     * Replace existing audit tags with new one
     */
    private void replaceAuditTag(Meta meta, Coding newAuditTag) {
        List<Coding> existingTags = meta.getTag();
        List<Coding> newTags = new ArrayList<>();
        
        // Keep existing non-audit tags
        if (existingTags != null) {
            for (Coding tag : existingTags) {
                if (!FhirAuditService.auditSystem().equals(tag.getSystem())) {
                    newTags.add(tag);
                }
            }
        }
        
        // Add the new audit tag
        newTags.add(newAuditTag);
        meta.setTag(newTags);
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

    // ========== LEGACY METHODS FOR BACKWARD COMPATIBILITY ==========
    // These will be deprecated once all services are migrated to the new approach
    
    /**
     * @deprecated Use applyMeta(Resource, MetaRequest) instead
     */
    @Deprecated
    public static void addAuditTags(Resource resource, String userId, String operation) {
        // Legacy method - kept for backward compatibility during migration
        if (resource == null) return;
        
        Meta meta = getOrCreateMeta(resource);
        String normalizedUserId = (userId != null && !userId.trim().isEmpty()) ? userId : "system";
        
        // Create audit tag using legacy approach
        String auditCode = switch (operation.toUpperCase()) {
            case "CREATE" -> "created-by";
            case "UPDATE" -> "updated-by";
            case "DELETE" -> "deleted-by";
            default -> "created-by";
        };
        
        Coding auditTag = new Coding()
                .setSystem("http://couchbase.fhir.com/fhir/custom-tags")
                .setCode(auditCode)
                .setDisplay(normalizedUserId);
        
        // Replace audit tags
        List<Coding> existingTags = meta.getTag();
        List<Coding> newTags = new ArrayList<>();
        
        if (existingTags != null) {
            for (Coding tag : existingTags) {
                if (!"http://couchbase.fhir.com/fhir/custom-tags".equals(tag.getSystem())) {
                    newTags.add(tag);
                }
            }
        }
        
        newTags.add(auditTag);
        meta.setTag(newTags);
    }
}
