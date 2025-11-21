package com.couchbase.admin.gsi.service;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service to create GSI indexes from gsi-indexes.sql file
 * These are critical indexes for Auth collections (users, clients, tokens, etc.)
 */
@Service
public class GsiIndexService {
    
    private static final Logger logger = LoggerFactory.getLogger(GsiIndexService.class);
    private static final String GSI_INDEXES_FILE = "gsi-indexes.sql";
    
    /**
     * Create all GSI indexes from gsi-indexes.sql file
     * Replaces placeholder bucket names with actual bucket name
     * 
     * @param cluster Couchbase cluster connection
     * @param bucketName Target bucket name (e.g., "fhir")
     * @throws Exception if index creation fails
     */
    public void createGsiIndexes(Cluster cluster, String bucketName) throws Exception {
        logger.info("📊 Creating GSI indexes from {} for bucket: {}", GSI_INDEXES_FILE, bucketName);
        
        // Read SQL statements from file
        List<String> sqlStatements = readGsiIndexFile();
        
        if (sqlStatements.isEmpty()) {
            logger.warn("⚠️ No GSI index statements found in {}", GSI_INDEXES_FILE);
            return;
        }
        
        logger.info("📋 Found {} GSI index statements", sqlStatements.size());
        logger.info("🚀 Creating GSI indexes in parallel for faster initialization...");
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        // Create all indexes in parallel using CompletableFuture
        List<CompletableFuture<Void>> indexCreationFutures = sqlStatements.stream()
            .filter(sql -> !sql.trim().isEmpty())
            .map(sql -> CompletableFuture.runAsync(() -> {
                // Replace placeholder bucket name with actual bucket name
                String processedSql = sql.replace("`fhir`", "`" + bucketName + "`");
                
                try {
                    logger.debug("Executing GSI: {}", processedSql);
                    cluster.query(processedSql, QueryOptions.queryOptions()
                        .timeout(Duration.ofMinutes(5)));
                    
                    successCount.incrementAndGet();
                    logger.info("✅ Created GSI index: {}", extractIndexName(processedSql));
                    
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                        skipCount.incrementAndGet();
                        logger.debug("⏭️  GSI index already exists: {}", extractIndexName(processedSql));
                    } else if (e.getMessage() != null && e.getMessage().contains("Build Already In Progress")) {
                        // Index is being built in background - this is normal
                        successCount.incrementAndGet();
                        logger.info("⏳ GSI index building in background: {}", extractIndexName(processedSql));
                    } else {
                        failCount.incrementAndGet();
                        logger.error("❌ Failed to create GSI index: {} - {}", 
                            extractIndexName(processedSql), e.getMessage());
                    }
                }
            }))
            .collect(Collectors.toList());
        
        // Wait for all index creation operations to complete
        try {
            CompletableFuture.allOf(indexCreationFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            logger.error("❌ Error during parallel GSI index creation: {}", e.getMessage());
        }
        
        logger.info("📊 GSI Index Creation Summary: {} created, {} skipped (already exist), {} failed", 
            successCount.get(), skipCount.get(), failCount.get());
        
        if (failCount.get() > 0) {
            logger.warn("⚠️ Some GSI indexes failed to create. Check logs for details.");
        }
    }
    
    /**
     * Read and parse GSI index SQL file
     * Returns list of SQL statements (one per line, comments stripped)
     */
    private List<String> readGsiIndexFile() throws Exception {
        List<String> sqlStatements = new ArrayList<>();
        
        try {
            // Use PathMatchingResourcePatternResolver - same approach as FTS indexes
            // This is more reliable for Spring Boot executable JARs
            PathMatchingResourcePatternResolver resolver = 
                new PathMatchingResourcePatternResolver(GsiIndexService.class.getClassLoader());
            
            String pattern = "classpath*:" + GSI_INDEXES_FILE;
            logger.debug("🔍 Looking for GSI indexes file with pattern: {}", pattern);
            
            Resource[] resources = resolver.getResources(pattern);
            
            if (resources.length == 0) {
                logger.error("❌ GSI indexes file not found: {}. File should be at classpath root.", GSI_INDEXES_FILE);
                logger.error("   This usually means the file wasn't included in the JAR during Maven build.");
                throw new RuntimeException("GSI indexes file not found: " + GSI_INDEXES_FILE);
            }
            
            logger.debug("✅ Found {} resource(s) matching pattern", resources.length);
            
            // Read from the first matching resource (should only be one)
            Resource resource = resources[0];
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // Trim whitespace
                    line = line.trim();
                    
                    // Skip empty lines and comments
                    if (line.isEmpty() || line.startsWith("--") || line.startsWith("#")) {
                        continue;
                    }
                    
                    // Add SQL statement
                    sqlStatements.add(line);
                }
            }
            
            logger.info("✅ Successfully read {} GSI index statements from {}", sqlStatements.size(), GSI_INDEXES_FILE);
            
        } catch (Exception e) {
            logger.error("❌ Failed to read GSI indexes file {}: {}", GSI_INDEXES_FILE, e.getMessage(), e);
            throw e;
        }
        
        return sqlStatements;
    }
    
    /**
     * Extract index name from CREATE INDEX statement for logging
     */
    private String extractIndexName(String sql) {
        try {
            // Simple extraction: look for "CREATE INDEX indexName" or "CREATE PRIMARY INDEX"
            if (sql.toUpperCase().contains("PRIMARY INDEX")) {
                int onPos = sql.toUpperCase().indexOf(" ON ");
                if (onPos > 0) {
                    String afterOn = sql.substring(onPos + 4).trim();
                    return "PRIMARY INDEX ON " + afterOn.split(" ")[0];
                }
                return "PRIMARY INDEX";
            } else {
                // Extract index name after "CREATE INDEX"
                int indexPos = sql.toUpperCase().indexOf("CREATE INDEX");
                if (indexPos >= 0) {
                    String afterIndex = sql.substring(indexPos + 12).trim();
                    int spacePos = afterIndex.indexOf(' ');
                    if (spacePos > 0) {
                        return afterIndex.substring(0, spacePos);
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to showing first 50 chars of SQL
        }
        
        // Fallback: show first 50 chars of SQL
        return sql.length() > 50 ? sql.substring(0, 50) + "..." : sql;
    }
}

