package com.couchbase.fhir.resources.service;

import org.hl7.fhir.r4.model.Coding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Focused audit service that acts as an "audit tag factory"
 * Returns audit tags (Coding objects) for given operations and users
 * Does NOT modify resources directly - that's the Meta helper's job
 */
@Service
public class FhirAuditService {

    private static final String AUDIT_TAG_SYSTEM = "http://couchbase.fhir.com/fhir/custom-tags";
    private static final String CREATED_BY_CODE = "created-by";
    private static final String UPDATED_BY_CODE = "updated-by";
    private static final String DELETED_BY_CODE = "deleted-by";

    private static final Logger logger = LoggerFactory.getLogger(FhirAuditService.class);
    /**
     * Create a new audit tag for the given operation and user
     * This is the main "factory" method for audit tags
     */
    public Coding newAuditTag(AuditOp op, String userId) {
        String normalized = normalizeUserId(userId);
        String code = switch (op) {
            case CREATE -> CREATED_BY_CODE;
            case UPDATE -> UPDATED_BY_CODE;
            case DELETE -> DELETED_BY_CODE;
        };
        return new Coding()
                .setSystem(AUDIT_TAG_SYSTEM)
                .setCode(code)
                .setDisplay(normalized);
    }

    /**
     * Create audit tag using current authenticated user
     */
    public Coding newAuditTag(AuditOp op) {
        return newAuditTag(op, getCurrentUserId());
    }

    /**
     * Normalize user ID to consistent format
     */
    public String normalizeUserId(String userId) {
        String u = (userId == null || userId.isBlank()) ? "anonymous" : userId.trim();
        if (!u.startsWith("user:") && !u.startsWith("system:")) {
            u = "user:" + u;
        }
        return u;
    }

    /**
     * Get the audit tag system URI
     */
    public static String auditSystem() {
        return AUDIT_TAG_SYSTEM;
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
}