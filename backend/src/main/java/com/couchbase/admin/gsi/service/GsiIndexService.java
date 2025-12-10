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

/**
 * Service to create GSI indexes from gsi-indexes.sql file
 * These are critical indexes for Auth collections (users, clients, tokens, etc.)
 */
@Service
public class GsiIndexService {
    
    private static final Logger logger = LoggerFactory.getLogger(GsiIndexService.class);
    private static final String GSI_INDEXES_FILE = "gsi-indexes.sql";
    
    /**
     * Create all GSI indexes from gsi-indexes.sql file synchronously
     * Replaces placeholder bucket names with actual bucket name
     * 
     * CREATE PRIMARY INDEX is synchronous - it blocks until the index is built and online.
     * This ensures indexes are ready before token generation can happen.
     * 
     * @param cluster Couchbase cluster connection
     * @param bucketName Target bucket name (e.g., "fhir")
     * @throws Exception if index creation fails
     */
    public void createGsiIndexes(Cluster cluster, String bucketName) throws Exception {
        logger.info("📊 Creating GSI indexes synchronously from {} for bucket: {}", GSI_INDEXES_FILE, bucketName);
        
        // Read SQL statements from file
        List<String> sqlStatements = readGsiIndexFile();
        
        if (sqlStatements.isEmpty()) {
            logger.warn("⚠️ No GSI index statements found in {}", GSI_INDEXES_FILE);
            return;
        }
        
        logger.info("📋 Found {} GSI index statements", sqlStatements.size());
        logger.info("🔨 Creating GSI indexes synchronously (will wait for indexes to be built and online)...");
        
        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        
        // Create indexes synchronously - CREATE PRIMARY INDEX blocks until built
        for (String sql : sqlStatements) {
            if (sql.trim().isEmpty()) {
                continue;
            }
            
            String processedSql = sql.replace("`fhir`", "`" + bucketName + "`");
            String indexName = extractIndexName(processedSql);
            
            try {
                logger.info("⏳ Creating index: {}", indexName);
                cluster.query(processedSql, QueryOptions.queryOptions().timeout(Duration.ofMinutes(5)));
                successCount++;
                logger.info("✅ Index created and online: {}", indexName);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    skipCount++;
                    logger.debug("⏭️ Index already exists: {}", indexName);
                } else if (e.getMessage() != null && e.getMessage().contains("Build Already In Progress")) {
                    successCount++;
                    logger.info("✅ Index building in progress: {}", indexName);
                } else {
                    failCount++;
                    logger.error("❌ Failed to create index {}: {}", indexName, e.getMessage());
                    // Don't throw - continue with other indexes
                }
            }
        }
        
        logger.info("📊 GSI Index Creation Summary: {} created, {} skipped, {} failed", 
            successCount, skipCount, failCount);
        
        if (failCount > 0) {
            logger.warn("⚠️ {} GSI indexes failed to create. Check logs for details.", failCount);
        } else {
            logger.info("✅ All GSI indexes are online and ready to serve queries");
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

