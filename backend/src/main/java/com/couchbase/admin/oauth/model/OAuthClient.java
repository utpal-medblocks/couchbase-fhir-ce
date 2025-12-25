package com.couchbase.admin.oauth.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * OAuth Client model for SMART on FHIR applications
 * Stored in: fhir.Admin.clients collection
 * 
 * Represents a registered OAuth 2.0 client application that can authenticate users
 * and access FHIR resources according to approved scopes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthClient {
    
    /**
     * Unique client identifier (UUID)
     * Used in OAuth authorization requests
     */
    private String clientId;
    
    /**
     * BCrypt hashed client secret (for confidential clients only)
     * Null for public clients (mobile/browser apps)
     */
    private String clientSecret;
    
    /**
     * User-friendly application name
     */
    private String clientName;
    
    /**
     * Optional publisher/organization URL
     */
    private String publisherUrl;
    
    /**
     * Client application type
     * - patient: Patient-facing standalone apps
     * - provider: Clinician/provider apps
     * - system: Backend services/systems
     */
    private String clientType;
    
    /**
     * Authentication type
     * - public: No client secret (mobile, browser apps)
     * - confidential: Uses client secret (server-side apps)
     */
    private String authenticationType;
    
    /**
     * Launch type
     * - standalone: App launches independently
     * - ehr-launch: Launched from within EHR context
     */
    private String launchType;
    
    /**
     * List of allowed redirect URIs
     * Authorization server will only redirect to these URIs after authentication
     */
    private List<String> redirectUris;
    
    /**
     * List of allowed OAuth scopes
     * e.g., ["openid", "fhirUser", "patient/*.read", "launch/patient"]
     */
    private List<String> scopes;
    
    /**
     * Whether PKCE (Proof Key for Code Exchange) is enabled
     * Recommended for public clients (mobile/browser apps)
     */
    private boolean pkceEnabled;
    
    /**
     * PKCE challenge method
     * - S256: SHA-256 hash (recommended)
     * - plain: Plain text (less secure)
     */
    private String pkceMethod;
    
    /**
     * Client status
     * - active: Can authenticate and access resources
     * - revoked: Access denied
     */
    private String status;
    
    /**
     * User ID who created this client
     */
    private String createdBy;
    
    /**
     * Timestamp when client was created
     */
    private Instant createdAt;
    
    /**
     * Timestamp of last token issuance using this client
     */
    private Instant lastUsed;

    /**
     * Optional reference to a Bulk Group id used for bulk operations
     */
    private String bulkGroupId;
    
    // Constructors
    
    public OAuthClient() {
        this.createdAt = Instant.now();
        this.status = "active";
        this.pkceEnabled = true;
        this.pkceMethod = "S256";
    }
    
    // Getters and Setters
    
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public String getClientSecret() {
        return clientSecret;
    }
    
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
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

    public String getBulkGroupId() {
        return bulkGroupId;
    }

    public void setBulkGroupId(String bulkGroupId) {
        this.bulkGroupId = bulkGroupId;
    }
    
    // Utility methods
    
    /**
     * Check if this is a confidential client (has client secret)
     */
    public boolean isConfidential() {
        return "confidential".equals(authenticationType);
    }
    
    /**
     * Check if this is a public client (no client secret)
     */
    public boolean isPublic() {
        return "public".equals(authenticationType);
    }
    
    /**
     * Check if client is active
     */
    public boolean isActive() {
        return "active".equals(status);
    }
    
    @Override
    public String toString() {
        return "OAuthClient{" +
                "clientId='" + clientId + '\'' +
                ", clientName='" + clientName + '\'' +
                ", clientType='" + clientType + '\'' +
                ", authenticationType='" + authenticationType + '\'' +
                ", status='" + status + '\'' +
                ", bulkGroupId='" + bulkGroupId + '\'' +
                '}';
    }
}

