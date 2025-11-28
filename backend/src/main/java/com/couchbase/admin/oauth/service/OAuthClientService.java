package com.couchbase.admin.oauth.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.oauth.model.OAuthClient;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing OAuth 2.0 clients
 * Handles CRUD operations for clients stored in Couchbase
 */
@Service
public class OAuthClientService {
    
    private static final Logger logger = LoggerFactory.getLogger(OAuthClientService.class);
    private static final String CONNECTION_NAME = "default";
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "clients";
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    /**
     * Get OAuth clients collection
     */
    private Collection getClientsCollection() {
        Cluster cluster = connectionService.getConnection(CONNECTION_NAME);
        Bucket bucket = cluster.bucket(BUCKET_NAME);
        Scope scope = bucket.scope(SCOPE_NAME);
        return scope.collection(COLLECTION_NAME);
    }
    
    /**
     * Create a new OAuth client
     * @param client Client to create
     * @param plainSecret Plain text client secret (for confidential clients)
     * @param createdBy ID of user creating this client
     * @return Created client
     */
    public OAuthClient createClient(OAuthClient client, String plainSecret, String createdBy) {
        logger.info("📱 Creating OAuth client: {}", client.getClientName());
        
        // Generate client ID
        String clientId = "app-" + UUID.randomUUID().toString();
        client.setClientId(clientId);
        
        // Hash client secret if confidential
        if ("confidential".equals(client.getAuthenticationType())) {
            if (plainSecret == null || plainSecret.isEmpty()) {
                throw new IllegalArgumentException("Client secret is required for confidential clients");
            }
            client.setClientSecret(passwordEncoder.encode(plainSecret));
        } else {
            client.setClientSecret(null); // Public clients don't have secrets
        }
        
        // Set metadata
        client.setCreatedBy(createdBy);
        client.setCreatedAt(Instant.now());
        client.setStatus("active");
        
        // Save to Couchbase
        Collection collection = getClientsCollection();
        collection.insert(clientId, client);
        
        logger.info("✅ OAuth client created: {} ({})", client.getClientName(), clientId);
        return client;
    }
    
    /**
     * Get all OAuth clients
     * @return List of all clients
     */
    public List<OAuthClient> getAllClients() {
        logger.debug("📋 Fetching all OAuth clients");
        
        Cluster cluster = connectionService.getConnection(CONNECTION_NAME);
        String query = "SELECT c.* FROM `" + BUCKET_NAME + "`.`" + SCOPE_NAME + "`.`" + COLLECTION_NAME + "` c";
        
        QueryResult result = cluster.query(query, QueryOptions.queryOptions());
        List<OAuthClient> clients = result.rowsAs(OAuthClient.class);
        
        logger.debug("Found {} OAuth clients", clients.size());
        return clients;
    }
    
    /**
     * Get OAuth client by ID
     * @param clientId Client ID
     * @return Client if found
     */
    public Optional<OAuthClient> getClientById(String clientId) {
        try {
            Collection collection = getClientsCollection();
            OAuthClient client = collection.get(clientId).contentAs(OAuthClient.class);
            return Optional.of(client);
        } catch (Exception e) {
            logger.debug("OAuth client not found: {}", clientId);
            return Optional.empty();
        }
    }
    
    /**
     * Update OAuth client
     * @param clientId Client ID
     * @param updates Updated client data
     * @return Updated client
     */
    public OAuthClient updateClient(String clientId, OAuthClient updates) {
        logger.info("🔄 Updating OAuth client: {}", clientId);
        
        Collection collection = getClientsCollection();
        OAuthClient existing = collection.get(clientId).contentAs(OAuthClient.class);
        
        // Update allowed fields
        if (updates.getClientName() != null) {
            existing.setClientName(updates.getClientName());
        }
        if (updates.getPublisherUrl() != null) {
            existing.setPublisherUrl(updates.getPublisherUrl());
        }
        if (updates.getRedirectUris() != null) {
            existing.setRedirectUris(updates.getRedirectUris());
        }
        if (updates.getScopes() != null) {
            existing.setScopes(updates.getScopes());
        }
        if (updates.getStatus() != null) {
            existing.setStatus(updates.getStatus());
        }
        
        collection.replace(clientId, existing);
        
        logger.info("✅ OAuth client updated: {}", clientId);
        return existing;
    }
    
    /**
     * Revoke OAuth client (set status to revoked)
     * @param clientId Client ID
     */
    public void revokeClient(String clientId) {
        logger.info("🚫 Revoking OAuth client: {}", clientId);
        
        Collection collection = getClientsCollection();
        OAuthClient client = collection.get(clientId).contentAs(OAuthClient.class);
        client.setStatus("revoked");
        collection.replace(clientId, client);
        
        logger.info("✅ OAuth client revoked: {}", clientId);
    }
    
    /**
     * Delete OAuth client permanently
     * @param clientId Client ID
     */
    public void deleteClient(String clientId) {
        logger.info("🗑️  Deleting OAuth client: {}", clientId);
        
        Collection collection = getClientsCollection();
        collection.remove(clientId);
        
        logger.info("✅ OAuth client deleted: {}", clientId);
    }
    
    /**
     * Verify client secret
     * @param clientId Client ID
     * @param plainSecret Plain text secret to verify
     * @return True if secret matches
     */
    public boolean verifyClientSecret(String clientId, String plainSecret) {
        Optional<OAuthClient> clientOpt = getClientById(clientId);
        if (clientOpt.isEmpty()) {
            return false;
        }
        
        OAuthClient client = clientOpt.get();
        if (client.getClientSecret() == null) {
            return false; // Public client has no secret
        }
        
        return passwordEncoder.matches(plainSecret, client.getClientSecret());
    }
    
    /**
     * Update last used timestamp
     * @param clientId Client ID
     */
    public void updateLastUsed(String clientId) {
        try {
            Collection collection = getClientsCollection();
            OAuthClient client = collection.get(clientId).contentAs(OAuthClient.class);
            client.setLastUsed(Instant.now());
            collection.replace(clientId, client);
        } catch (Exception e) {
            logger.warn("Failed to update last used timestamp for client {}: {}", clientId, e.getMessage());
        }
    }
}

