package com.couchbase.admin.users.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
     * NOTE: Intentionally no Jackson write-only / ignore annotation so that the
     * hash is actually persisted in Couchbase. API controllers MUST avoid
     * exposing this value by mapping to a response DTO.
     */
    private String passwordHash;
    
    /**
     * Transient plain-text password when creating/updating a user.
     * This field is NOT persisted to Couchbase and is intended to carry
     * the plain password during user creation flows (used only when
     * delegating to Keycloak). Controllers should set this when
     * creating/updating users that require a password.
     */
    private transient String passwordPlain;
    
    /**
     * User role: "admin", "developer", "patient", or "practitioner"
     * - admin: Full access to Admin UI (Dashboard, Tokens, Users, etc.) - scopes: user/*.*,  system/*.*
     * - developer: Limited Admin UI access (Tokens and Client Registration only) - scopes: user/*.*
     * - patient: Cannot login to UI - created during sample data load for SMART app testing
     * - practitioner: Cannot login to UI - created during sample data load for SMART app testing
     * Note: Only admin and developer roles can login to the UI, all use local auth
     */
    private String role;

    // Note: Scopes are NOT stored in user document
    // Scopes come from the OAuth client registration, not the user
    // Admin/Developer users access tokens via /api/admin/tokens endpoint
    
    /**
     * Authentication method: always "local"
     * All users use local email/password authentication
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
    
    /**
     * Optional default patient ID for SMART on FHIR patient context
     * Used when user launches SMART apps without explicit patient selection
     * Format: "patient-id" (just the ID, not "Patient/patient-id")
     */
    private String defaultPatientId;
    
    /**
     * Optional FHIR resource reference for SMART on FHIR users
     * Links the user account to their FHIR resource (Patient/Practitioner)
     * Format: "ResourceType/resource-id" (e.g., "Patient/example", "Practitioner/practitioner-1")
     * This value is added to JWT tokens as the "fhirUser" claim and "patient" claim (if Patient resource)
     */
    private String fhirUser;

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

    public String getPasswordPlain() {
        return passwordPlain;
    }

    public void setPasswordPlain(String passwordPlain) {
        this.passwordPlain = passwordPlain;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    // Scopes removed - they come from OAuth client registration, not user profile

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

    public String getDefaultPatientId() {
        return defaultPatientId;
    }

    public void setDefaultPatientId(String defaultPatientId) {
        this.defaultPatientId = defaultPatientId;
    }

    public String getFhirUser() {
        return fhirUser;
    }

    public void setFhirUser(String fhirUser) {
        this.fhirUser = fhirUser;
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
