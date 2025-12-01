package com.couchbase.fhir.auth.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Arrays;
import java.util.List;

/**
 * Composite RegisteredClientRepository that delegates to multiple repositories.
 * 
 * This allows Spring Authorization Server to find OAuth clients from:
 * 1. In-memory repository (admin-ui client for development)
 * 2. Couchbase repository (SMART on FHIR apps registered via UI)
 * 
 * The first repository to return a non-null result wins.
 */
public class CompositeRegisteredClientRepository implements RegisteredClientRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(CompositeRegisteredClientRepository.class);
    
    private final List<RegisteredClientRepository> repositories;
    
    public CompositeRegisteredClientRepository(RegisteredClientRepository... repositories) {
        this.repositories = Arrays.asList(repositories);
        logger.info("🔗 Initialized CompositeRegisteredClientRepository with {} repositories", repositories.length);
    }
    
    @Override
    public void save(RegisteredClient registeredClient) {
        // Delegate to all repositories
        for (RegisteredClientRepository repository : repositories) {
            try {
                repository.save(registeredClient);
            } catch (Exception e) {
                logger.warn("⚠️ Repository {} failed to save client {}: {}", 
                    repository.getClass().getSimpleName(), 
                    registeredClient.getClientId(), 
                    e.getMessage());
            }
        }
    }
    
    @Override
    public RegisteredClient findById(String id) {
        logger.debug("🔍 Searching for client by ID: {}", id);
        
        for (RegisteredClientRepository repository : repositories) {
            try {
                RegisteredClient client = repository.findById(id);
                if (client != null) {
                    logger.debug("✅ Found client {} in {}", id, repository.getClass().getSimpleName());
                    return client;
                }
            } catch (Exception e) {
                logger.warn("⚠️ Repository {} failed to find client by ID {}: {}", 
                    repository.getClass().getSimpleName(), id, e.getMessage());
            }
        }
        
        logger.debug("❌ Client not found by ID: {}", id);
        return null;
    }
    
    @Override
    public RegisteredClient findByClientId(String clientId) {
        logger.debug("🔍 Searching for client by clientId: {}", clientId);
        
        for (RegisteredClientRepository repository : repositories) {
            try {
                RegisteredClient client = repository.findByClientId(clientId);
                if (client != null) {
                    logger.info("✅ Found client {} in {}", clientId, repository.getClass().getSimpleName());
                    return client;
                }
            } catch (Exception e) {
                logger.warn("⚠️ Repository {} failed to find client by clientId {}: {}", 
                    repository.getClass().getSimpleName(), clientId, e.getMessage());
            }
        }
        
        logger.warn("❌ Client not found by clientId: {}", clientId);
        return null;
    }
}

