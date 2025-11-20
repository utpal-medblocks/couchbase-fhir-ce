package com.couchbase.admin.tokens.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/**
 * API Token model for OAuth2 client credentials
 * Stored in fhir.Admin.tokens collection
 * 
 * Each token represents an OAuth2 client that can obtain access tokens
 * via client_credentials grant
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Token {
    private String id; // Token ID = OAuth2 client_id
    private String userId; // Owner of the token (user email/ID)
    private String appName; // Application name for this token
    private String clientId; // OAuth2 client_id
    private String clientSecretHash; // BCrypt hash of client_secret (never store plain secret)
    private String status; // "active", "revoked"
    private Instant createdAt;
    private Instant lastUsedAt;
    private String createdBy; // Who created this token (usually same as userId)
    private String[] scopes; // FHIR scopes: ["patient/*.read", "patient/*.write", etc.]

    // Default constructor for Jackson
    public Token() {
    }

    // Constructor for creating new tokens
    public Token(String id, String userId, String appName, String clientId,
                 String clientSecretHash, String createdBy, String[] scopes) {
        this.id = id;
        this.userId = userId;
        this.appName = appName;
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.status = "active";
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
        this.scopes = scopes;
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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public void setClientSecretHash(String clientSecretHash) {
        this.clientSecretHash = clientSecretHash;
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

    @Override
    public String toString() {
        return "Token{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", appName='" + appName + '\'' +
                ", clientId='" + clientId + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}

