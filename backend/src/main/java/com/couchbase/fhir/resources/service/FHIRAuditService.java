package com.couchbase.fhir.resources.service;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FHIRAuditService {
    
    private static final Logger logger = LoggerFactory.getLogger(FHIRAuditService.class);
    
    /**
     * Add comprehensive audit information to resource meta
     */
    public void addAuditInfoToMeta(IBaseResource resource, String userId, String operation) {
        UserAuditInfo auditInfo = getCurrentUserAuditInfo();
        addAuditInfoToMeta(resource, auditInfo, operation);
    }
    
    /**
     * Add comprehensive audit information to resource meta with detailed audit info
     */
    public void addAuditInfoToMeta(IBaseResource resource, UserAuditInfo auditInfo, String operation) {
        try {
            Meta meta = getOrCreateMeta(resource);
            
            // Update timestamp
            meta.setLastUpdated(new Date());
            
            // Add audit tags for who and what
            addAuditTags(meta, auditInfo, operation);
            
            // Add security labels if needed
            addSecurityLabels(meta, auditInfo);
            
            // Set meta back to resource
            setMetaOnResource(resource, meta);
            
            // logger.info("✅ Added audit info to {} resource - User: {}, Operation: {}", 
            //     resource.fhirType(), auditInfo.getUserId(), operation);
                
        } catch (Exception e) {
            logger.error("❌ Failed to add audit info to resource: {}", e.getMessage(), e);
            // Don't throw - audit failure shouldn't break resource processing
        }
    }
    
    /**
     * Add minimal audit tags - only created-by user information
     */
    private void addAuditTags(Meta meta, UserAuditInfo auditInfo, String operation) {
        // Only add who performed the action - keep it simple
        meta.addTag(new Coding()
            .setSystem("http://terminology.hl7.org/CodeSystem/common-tags")
            .setCode("created-by")
            .setDisplay("user:" + auditInfo.getUserId()));
    }
    
    /**
     * Add security labels for access control - skip to keep meta minimal
     */
    private void addSecurityLabels(Meta meta, UserAuditInfo auditInfo) {
        // Skip security labels to keep meta minimal as requested
    }
    
    /**
     * Get current authenticated user from Spring Security context
     */
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        
        // For different auth types:
        if (authentication instanceof OAuth2AuthenticationToken) {
            // OAuth2 - extract from attributes
            OAuth2AuthenticationToken oauth2Auth = (OAuth2AuthenticationToken) authentication;
            Object sub = oauth2Auth.getPrincipal().getAttribute("sub");
            Object preferredUsername = oauth2Auth.getPrincipal().getAttribute("preferred_username");
            Object userId = oauth2Auth.getPrincipal().getAttribute("user_id");
            
            // Use preferred order: user_id > preferred_username > sub > name
            if (userId != null) return userId.toString();
            if (preferredUsername != null) return preferredUsername.toString();
            if (sub != null) return sub.toString();
            return authentication.getName();
        } else {
            // Basic auth or other - use name
            return authentication.getName();
        }
    }
    
    /**
     * Get user details including roles/permissions
     */
    public UserAuditInfo getCurrentUserAuditInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return new UserAuditInfo("anonymous", "system", Collections.emptySet());
        }
        
        String userId = getCurrentUserId();
        String userType = determineUserType(authentication);
        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        
        UserAuditInfo auditInfo = new UserAuditInfo(userId, userType, roles);
        
        // Add additional context if available
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauth2Auth = (OAuth2AuthenticationToken) authentication;
            Object department = oauth2Auth.getPrincipal().getAttribute("department");
            Object sessionId = oauth2Auth.getPrincipal().getAttribute("session_id");
            
            if (department != null) auditInfo.setDepartment(department.toString());
            if (sessionId != null) auditInfo.setSessionId(sessionId.toString());
        }
        
        return auditInfo;
    }
    
    /**
     * Determine user type based on authentication
     */
    private String determineUserType(Authentication auth) {
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM"))) {
            return "system";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return "administrator";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CLINICIAN"))) {
            return "clinician";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATIENT"))) {
            return "patient";
        } else {
            return "user";
        }
    }
    
    /**
     * Get or create Meta object for a resource
     */
    private Meta getOrCreateMeta(IBaseResource resource) {
        if (resource instanceof DomainResource) {
            DomainResource domainResource = (DomainResource) resource;
            Meta meta = domainResource.getMeta();
            if (meta == null) {
                meta = new Meta();
                domainResource.setMeta(meta);
            }
            return meta;
        } else if (resource instanceof Resource) {
            Resource res = (Resource) resource;
            Meta meta = res.getMeta();
            if (meta == null) {
                meta = new Meta();
                res.setMeta(meta);
            }
            return meta;
        }
        
        // Fallback - create new Meta
        return new Meta();
    }
    
    /**
     * Set Meta object back to resource
     */
    private void setMetaOnResource(IBaseResource resource, Meta meta) {
        if (resource instanceof DomainResource) {
            ((DomainResource) resource).setMeta(meta);
        } else if (resource instanceof Resource) {
            ((Resource) resource).setMeta(meta);
        }
    }
    
    /**
     * Add Bundle-specific audit context
     */
    public void addBundleAuditContext(IBaseResource resource, String bundleId, String operation) {
        // Skip Bundle context to keep meta minimal as requested
    }
} 