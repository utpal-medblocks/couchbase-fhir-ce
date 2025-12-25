package com.couchbase.admin.oauth.service;

import com.couchbase.admin.oauth.model.OAuthClient;

import java.util.List;
import java.util.Optional;

public interface KeycloakOAuthClientManager {
    OAuthClient createClient(OAuthClient client, String plainSecret, String createdBy);
    List<OAuthClient> getAllClients();
    Optional<OAuthClient> getClientById(String clientId);
    OAuthClient updateClient(String clientId, OAuthClient updates);
    void revokeClient(String clientId);
    void deleteClient(String clientId);
    boolean verifyClientSecret(String clientId, String plainSecret);
    void updateLastUsed(String clientId);
    
    // Bulk group operations
    void attachBulkGroup(String clientId, String bulkGroupId);
    Optional<String> getBulkGroup(String clientId);
    void detachBulkGroup(String clientId);
}
