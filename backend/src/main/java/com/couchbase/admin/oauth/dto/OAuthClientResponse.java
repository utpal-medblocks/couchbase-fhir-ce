package com.couchbase.admin.oauth.dto;

import com.couchbase.admin.oauth.model.OAuthClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * DTO for OAuth Client API responses
 * Excludes sensitive information like client secret
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthClientResponse {
    
    private String clientId;
    private String clientName;
    private String publisherUrl;
    private String clientType;
    private String authenticationType;
    private String launchType;
    private List<String> redirectUris;
    private List<String> scopes;
    private boolean pkceEnabled;
    private String pkceMethod;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant lastUsed;
    
    // For newly created confidential clients only - shown once
    private String clientSecret;
    
    public static OAuthClientResponse from(OAuthClient client) {
        OAuthClientResponse dto = new OAuthClientResponse();
        dto.setClientId(client.getClientId());
        dto.setClientName(client.getClientName());
        dto.setPublisherUrl(client.getPublisherUrl());
        dto.setClientType(client.getClientType());
        dto.setAuthenticationType(client.getAuthenticationType());
        dto.setLaunchType(client.getLaunchType());
        dto.setRedirectUris(client.getRedirectUris());
        dto.setScopes(client.getScopes());
        dto.setPkceEnabled(client.isPkceEnabled());
        dto.setPkceMethod(client.getPkceMethod());
        dto.setStatus(client.getStatus());
        dto.setCreatedBy(client.getCreatedBy());
        dto.setCreatedAt(client.getCreatedAt());
        dto.setLastUsed(client.getLastUsed());
        // Note: clientSecret is NOT included for security
        return dto;
    }
    
    public static OAuthClientResponse fromWithSecret(OAuthClient client, String plainSecret) {
        OAuthClientResponse dto = from(client);
        dto.setClientSecret(plainSecret); // Include plain secret only for newly created clients
        return dto;
    }
    
    // Getters and Setters
    
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public String getClientName() {
        return clientName;
    }
    
    public void setClientName(String clientName) {
        this.clientName = clientName;
    }
    
    public String getPublisherUrl() {
        return publisherUrl;
    }
    
    public void setPublisherUrl(String publisherUrl) {
        this.publisherUrl = publisherUrl;
    }
    
    public String getClientType() {
        return clientType;
    }
    
    public void setClientType(String clientType) {
        this.clientType = clientType;
    }
    
    public String getAuthenticationType() {
        return authenticationType;
    }
    
    public void setAuthenticationType(String authenticationType) {
        this.authenticationType = authenticationType;
    }
    
    public String getLaunchType() {
        return launchType;
    }
    
    public void setLaunchType(String launchType) {
        this.launchType = launchType;
    }
    
    public List<String> getRedirectUris() {
        return redirectUris;
    }
    
    public void setRedirectUris(List<String> redirectUris) {
        this.redirectUris = redirectUris;
    }
    
    public List<String> getScopes() {
        return scopes;
    }
    
    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }
    
    public boolean isPkceEnabled() {
        return pkceEnabled;
    }
    
    public void setPkceEnabled(boolean pkceEnabled) {
        this.pkceEnabled = pkceEnabled;
    }
    
    public String getPkceMethod() {
        return pkceMethod;
    }
    
    public void setPkceMethod(String pkceMethod) {
        this.pkceMethod = pkceMethod;
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
    
    public Instant getLastUsed() {
        return lastUsed;
    }
    
    public void setLastUsed(Instant lastUsed) {
        this.lastUsed = lastUsed;
    }
    
    public String getClientSecret() {
        return clientSecret;
    }
    
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}

