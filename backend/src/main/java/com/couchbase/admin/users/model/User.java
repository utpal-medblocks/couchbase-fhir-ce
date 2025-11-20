package com.couchbase.admin.users.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * User model for Couchbase FHIR Server
 * Stored in: fhir.Admin.users collection
 * 
 * Supports both local authentication (username/password) and social auth (Google/GitHub)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class User {
    
    /**
     * Unique user identifier (e.g., "john.doe")
     * Used as document key in Couchbase
     */
    private String id;
    
    /**
     * Display name (e.g., "John Doe")
     */
    private String username;
    
    /**
     * Email address (unique, used for social auth)
     */
    private String email;
    
    /**
     * BCrypt hashed password (null for social auth only users)
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;
    
    /**
     * User role: "admin", "developer", or "smart_user"
     * - admin: Full access to Admin UI and user management
     * - developer: Full Admin-UI without access to Users Page
     * - smart_user: No login, just for SMART apps
     */
    private String role;

    /**
     * Allowed scopes for this user
     */
    private String[] allowedScopes;
    
    /**
     * Authentication method: "local" or "social"
     * - local: Email/password authentication
     * - social: OAuth (Google, GitHub, etc.)
     */
    private String authMethod;
    
    /**
     * Account status: "active", "inactive", "suspended"
     */
    private String status;
    
    /**
     * User ID who created this user (e.g., "admin@couchbase.com")
     */
    private String createdBy;
    
    /**
     * Timestamp when user was created
     */
    private Instant createdAt;
    
    /**
     * Timestamp of last successful login
     */
    private Instant lastLogin;
    
    /**
     * Optional profile picture URL (from social auth)
     */
    private String profilePicture;
    
    /**
     * Optional social auth provider user ID
     */
    private String socialAuthId;

    // Constructors
    
    public User() {
        this.createdAt = Instant.now();
        this.status = "active";
    }
    
    public User(String id, String username, String email, String role) {
        this();
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
    }

    // Getters and Setters
    
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
        // Auto-assign scopes based on role if not already set
        if (this.allowedScopes == null || this.allowedScopes.length == 0) {
            if ("admin".equals(role)) {
                this.allowedScopes = new String[]{"user/*.*", "system/*.*"};
            } else if ("developer".equals(role)) {
                this.allowedScopes = new String[]{"user/*.*"};
            } else if ("smart_user".equals(role)) {
                this.allowedScopes = new String[]{"openid", "profile", "launch/patient", "patient/*.read", "offline_access"};
            }
        }
    }

    public String[] getAllowedScopes() {
        if (allowedScopes == null || allowedScopes.length == 0) {
            if ("admin".equals(role)) {
                return new String[]{"user/*.*", "system/*.*"};
            } else if ("developer".equals(role)) {
                return new String[]{"user/*.*"};
            } else if ("smart_user".equals(role)) {
                return new String[]{"openid", "profile", "launch/patient", "patient/*.read", "offline_access"};
            }
        }
        return allowedScopes;
    }

    public void setAllowedScopes(String[] allowedScopes) {
        this.allowedScopes = allowedScopes;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(Instant lastLogin) {
        this.lastLogin = lastLogin;
    }

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }

    public String getSocialAuthId() {
        return socialAuthId;
    }

    public void setSocialAuthId(String socialAuthId) {
        this.socialAuthId = socialAuthId;
    }

    // Utility methods
    
    /**
     * Check if user is an admin
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }

    /**
     * Check if user is a developer
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDeveloper() {
        return "developer".equals(role);
    }
    
    /**
     * Check if user uses local authentication
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isLocalAuth() {
        return "local".equals(authMethod);
    }
    
    /**
     * Check if user is active
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isActive() {
        return "active".equals(status);
    }
    
    /**
     * Get user initials for UI (e.g., "JD" for "John Doe")
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getInitials() {
        if (username == null || username.isEmpty()) {
            return "U";
        }
        String[] parts = username.split(" ");
        if (parts.length >= 2) {
            return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
        }
        return username.substring(0, Math.min(2, username.length())).toUpperCase();
    }

    @Override
    public String toString() {
        return "User{" +
                "id='" + id + '\'' +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", role='" + role + '\'' +
                ", authMethod='" + authMethod + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
