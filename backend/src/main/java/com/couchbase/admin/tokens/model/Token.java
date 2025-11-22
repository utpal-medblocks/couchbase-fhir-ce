package com.couchbase.admin.tokens.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/**
 * JWT Access Token metadata
 * Stored in fhir.Admin.tokens collection
 * 
 * Stores metadata about issued JWT tokens for tracking and revocation.
 * The actual JWT is issued by Spring Authorization Server using OAuth signing key.
 * This document allows us to:
 * - Track active tokens and revoke them
 * - Maintain an in-memory cache of active JTIs for fast validation
 * - Audit token usage and lifecycle
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Token {
    private String id; // Document ID: "token::<jti>"
    private String type; // Always "jwt_access_token"
    private String jti; // JWT ID (unique identifier for this token)
    private String userId; // Owner of the token (user email/ID)
    private String appName; // Application name for this token
    private String status; // "active", "revoked"
    private String[] scopes; // FHIR scopes: ["system/*.*", "user/*.*", etc.]
    private Instant createdAt;
    private Instant expiresAt; // Token expiration (from JWT exp claim)
    private Instant lastUsedAt; // Last time this token was used
    private String createdBy; // Who created this token (usually same as userId)

    // Default constructor for Jackson
    public Token() {
    }

    // Constructor for creating new JWT tokens
    public Token(String jti, String userId, String appName, String[] scopes, 
                 Instant expiresAt, String createdBy) {
        this.id = "token::" + jti;
        this.type = "jwt_access_token";
        this.jti = jti;
        this.userId = userId;
        this.appName = appName;
        this.scopes = scopes;
        this.status = "active";
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String[] getScopes() {
        return scopes;
    }

    public void setScopes(String[] scopes) {
        this.scopes = scopes;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    // Helper methods
    @JsonIgnore
    public boolean isActive() {
        return "active".equals(status);
    }

    @JsonIgnore
    public boolean isRevoked() {
        return "revoked".equals(status);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Token token = (Token) o;
        return Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @JsonIgnore
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    @Override
    public String toString() {
        return "Token{" +
                "id='" + id + '\'' +
                ", jti='" + jti + '\'' +
                ", userId='" + userId + '\'' +
                ", appName='" + appName + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}

