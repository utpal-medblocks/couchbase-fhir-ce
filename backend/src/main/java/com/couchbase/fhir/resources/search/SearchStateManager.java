package com.couchbase.fhir.resources.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages search state for pagination operations, particularly for _revinclude searches.
 * Provides caching, expiration, and cleanup of search states.
 */
@Service
public class SearchStateManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchStateManager.class);
    
    // In-memory cache for search states
    private final Map<String, SearchState> searchStateCache = new ConcurrentHashMap<>();
    
    @Value("${fhir.search.state.ttl.minutes:15}")
    private int searchStateTtlMinutes;
    
    @Value("${fhir.search.state.cleanup.interval.minutes:5}")
    private int cleanupIntervalMinutes;
    
    /**
     * Store a search state and return a unique token
     * 
     * @param state The search state to store
     * @return Unique token for retrieving the state
     */
    public String storeSearchState(SearchState state) {
        String token = generateUniqueToken();
        
        // Set expiration time
        long expirationTime = System.currentTimeMillis() + 
            TimeUnit.MINUTES.toMillis(searchStateTtlMinutes);
        state.setExpiresAt(expirationTime);
        
        searchStateCache.put(token, state);
        
        logger.debug("Stored search state with token: {} (expires in {} minutes)", 
                    token, searchStateTtlMinutes);
        
        return token;
    }
    
    /**
     * Retrieve a search state by token
     * 
     * @param token The token to look up
     * @return SearchState if found and not expired, null otherwise
     */
    public SearchState retrieveSearchState(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        SearchState state = searchStateCache.get(token);
        if (state == null) {
            logger.debug("Search state not found for token: {}", token);
            return null;
        }
        
        if (state.isExpired()) {
            logger.debug("Search state expired for token: {}", token);
            searchStateCache.remove(token);
            return null;
        }
        
        // Optional: Extend expiration on access (sliding window)
        extendExpiration(state);
        
        return state;
    }
    
    /**
     * Remove a search state from cache
     * 
     * @param token The token to remove
     */
    public void removeSearchState(String token) {
        if (token != null) {
            searchStateCache.remove(token);
            logger.debug("Removed search state for token: {}", token);
        }
    }
    
    /**
     * Check if a search state is expired
     * 
     * @param state The search state to check
     * @return true if expired, false otherwise
     */
    public boolean isExpired(SearchState state) {
        return state == null || state.isExpired();
    }
    
    /**
     * Get current cache size (for monitoring)
     * 
     * @return Number of cached search states
     */
    public int getCacheSize() {
        return searchStateCache.size();
    }
    
    /**
     * Background cleanup task to remove expired search states
     */
    @Scheduled(fixedDelayString = "#{${fhir.search.state.cleanup.interval.minutes:5} * 60 * 1000}")
    public void cleanupExpiredStates() {
        int initialSize = searchStateCache.size();
        
        searchStateCache.entrySet().removeIf(entry -> 
            entry.getValue().isExpired());
        
        int finalSize = searchStateCache.size();
        int removedCount = initialSize - finalSize;
        
        if (removedCount > 0) {
            logger.info("Cleaned up {} expired search states. Cache size: {} -> {}", 
                       removedCount, initialSize, finalSize);
        }
    }
    
    /**
     * Generate a unique token for search state identification
     * 
     * @return Unique token string
     */
    private String generateUniqueToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Extend the expiration time of a search state (sliding window approach)
     * 
     * @param state The search state to extend
     */
    private void extendExpiration(SearchState state) {
        long newExpirationTime = System.currentTimeMillis() + 
            TimeUnit.MINUTES.toMillis(searchStateTtlMinutes);
        state.setExpiresAt(newExpirationTime);
    }
    
    /**
     * Clear all cached search states (for testing or maintenance)
     */
    public void clearAllStates() {
        int size = searchStateCache.size();
        searchStateCache.clear();
        logger.info("Cleared all {} search states from cache", size);
    }
}
