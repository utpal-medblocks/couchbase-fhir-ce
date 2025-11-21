package com.couchbase.admin.users.dto;

import com.couchbase.admin.users.model.User;
import java.time.Instant;

/**
 * DTO for exposing user data via REST APIs without the passwordHash.
 */
public class UserResponse {
    private String id;
    private String username;
    private String email;
    private String role;
    private String[] allowedScopes;
    private String authMethod;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant lastLogin;
    private String profilePicture;
    private String socialAuthId;

    public static UserResponse from(User u) {
        if (u == null) return null;
        UserResponse r = new UserResponse();
        r.id = u.getId();
        r.username = u.getUsername();
        r.email = u.getEmail();
        r.role = u.getRole();
        r.allowedScopes = u.getAllowedScopes();
        r.authMethod = u.getAuthMethod();
        r.status = u.getStatus();
        r.createdBy = u.getCreatedBy();
        r.createdAt = u.getCreatedAt();
        r.lastLogin = u.getLastLogin();
        r.profilePicture = u.getProfilePicture();
        r.socialAuthId = u.getSocialAuthId();
        return r;
    }

    // Getters only (immutable outward)
    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public String[] getAllowedScopes() { return allowedScopes; }
    public String getAuthMethod() { return authMethod; }
    public String getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLogin() { return lastLogin; }
    public String getProfilePicture() { return profilePicture; }
    public String getSocialAuthId() { return socialAuthId; }
}
