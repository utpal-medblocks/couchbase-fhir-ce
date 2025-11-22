package com.couchbase.admin.tokens.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of active JWT token IDs (JTI) for fast validation
 * 
 * This service maintains a HashSet of active JTIs to enable fast O(1) lookups
 * during request validation without hitting the database on every request.
 * 
 * Lifecycle:
 * 1. Load all active JTIs at startup (if FHIR bucket initialized)
 * 2. Update cache when tokens are created/revoked/deleted
 * 3. Periodic sync every 5 minutes to catch manual DB changes
 * 4. Periodic cleanup to remove expired tokens from cache
 */
@Service
public class JwtTokenCacheService {
    
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenCacheService.class);
    private static final String BUCKET_NAME = "fhir";
    private static final String SCOPE_NAME = "Admin";
    private static final String COLLECTION_NAME = "tokens";
    private static final String DEFAULT_CONNECTION = "default";
    
    @Autowired
    private ConnectionService connectionService;
    
    // Thread-safe set of active JWT IDs
    private final Set<String> activeJtis = ConcurrentHashMap.newKeySet();
    
    // Flag to track if cache has been initialized
    private volatile boolean initialized = false;
    
    /**
     * Load all active tokens from database into cache
     * Called at startup and periodically for sync
     */
    public void loadActiveTokens() {
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                logger.debug("⏭️ [JWT-CACHE] No connection available, skipping cache load");
                return;
            }
            
            // Check if collection exists (fast N1QL check)
            if (!isTokenCollectionAvailable(cluster)) {
                logger.debug("⏭️ [JWT-CACHE] Token collection not available yet, skipping cache load");
                return;
            }
            
            // Query active tokens (not expired, status = active)
            // Note: expiresAt is stored as epoch seconds with decimal (from Instant, e.g. 1763588846.078446)
            // NOW_MILLIS() returns milliseconds, so divide by 1000 to compare
            String sql = String.format(
                "SELECT RAW jti FROM `%s`.`%s`.`%s` " +
                "WHERE status = 'active' " +
                "AND (expiresAt IS NULL OR expiresAt > (NOW_MILLIS() / 1000))",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
            );
            
            long start = System.nanoTime();
            var result = cluster.query(sql);
            var jtis = result.rowsAs(String.class);
            
            // Clear and reload cache
            activeJtis.clear();
            activeJtis.addAll(jtis);
            
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            logger.info("✅ [JWT-CACHE] Loaded {} active token JTIs in {} ms", jtis.size(), elapsedMs);
            initialized = true;
            
        } catch (Exception e) {
            logger.error("❌ [JWT-CACHE] Failed to load active tokens: {}", e.getMessage());
        }
    }
    
    /**
     * Check if a JWT ID is in the active cache
     * This is called on EVERY request, so must be very fast (O(1))
     */
    public boolean isActive(String jti) {
        if (!initialized) {
            logger.warn("⚠️ [JWT-CACHE] Cache not initialized yet, token validation may be inaccurate");
            return false;
        }
        return activeJtis.contains(jti);
    }
    
    /**
     * Add a newly created token to the cache
     */
    public void addToken(String jti) {
        activeJtis.add(jti);
        logger.debug("➕ [JWT-CACHE] Added JTI to cache: {}", jti);
    }
    
    /**
     * Remove a revoked/deleted token from the cache
     */
    public void removeToken(String jti) {
        activeJtis.remove(jti);
        logger.debug("➖ [JWT-CACHE] Removed JTI from cache: {}", jti);
    }
    
    /**
     * Get cache statistics
     */
    public int getCacheSize() {
        return activeJtis.size();
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Periodic sync: Reload cache from database every 5 minutes
     * This catches tokens that were manually added/removed via Couchbase console
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void periodicSync() {
        if (!initialized) {
            return; // Skip sync if never initialized
        }
        logger.debug("🔄 [JWT-CACHE] Starting periodic sync...");
        loadActiveTokens();
    }
    
    /**
     * Periodic cleanup: Remove expired tokens from cache every hour
     * This prevents memory buildup from expired tokens
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupExpired() {
        if (!initialized) {
            return;
        }
        
        try {
            Cluster cluster = connectionService.getConnection(DEFAULT_CONNECTION);
            if (cluster == null) {
                return;
            }
            
            // Query expired tokens
            // Note: expiresAt is stored as epoch seconds with decimal (from Instant)
            String sql = String.format(
                "SELECT RAW jti FROM `%s`.`%s`.`%s` " +
                "WHERE expiresAt IS NOT NULL AND expiresAt < (NOW_MILLIS() / 1000)",
                BUCKET_NAME, SCOPE_NAME, COLLECTION_NAME
            );
            
            var result = cluster.query(sql);
            var expiredJtis = result.rowsAs(String.class);
            
            // Remove from cache
            int removed = 0;
            for (String jti : expiredJtis) {
                if (activeJtis.remove(jti)) {
                    removed++;
                }
            }
            
            if (removed > 0) {
                logger.info("🧹 [JWT-CACHE] Cleaned up {} expired tokens from cache", removed);
            }
            
        } catch (Exception e) {
            logger.error("❌ [JWT-CACHE] Failed to cleanup expired tokens: {}", e.getMessage());
        }
    }
    
    /**
     * Fast check if token collection exists (same pattern as AuthorizationServerConfig)
     */
    private boolean isTokenCollectionAvailable(Cluster cluster) {
        try {
            String sql = "SELECT RAW name FROM system:keyspaces " +
                        "WHERE `bucket` = $bucket AND `scope` = $scope AND `name` = $name LIMIT 1";
            var result = cluster.query(sql, QueryOptions.queryOptions()
                .parameters(JsonObject.create()
                    .put("bucket", BUCKET_NAME)
                    .put("scope", SCOPE_NAME)
                    .put("name", COLLECTION_NAME)));
            return !result.rowsAs(String.class).isEmpty();
        } catch (Exception e) {
            logger.debug("Collection existence check failed: {}", e.getMessage());
            return false;
        }
    }
}

