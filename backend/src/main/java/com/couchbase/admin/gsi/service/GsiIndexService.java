package com.couchbase.admin.gsi.service;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
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
        
        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        
        // Execute each SQL statement
        for (String sql : sqlStatements) {
            // Skip empty statements
            if (sql.trim().isEmpty()) {
                continue;
            }
            
            // Replace placeholder bucket names with actual bucket name
            // The file uses both `fhir` and `acme-fhir` as examples
            String processedSql = sql
                .replace("`fhir`", "`" + bucketName + "`")
                .replace("`acme-fhir`", "`" + bucketName + "`");
            
            try {
                logger.debug("Executing GSI: {}", processedSql);
                cluster.query(processedSql, QueryOptions.queryOptions()
                    .timeout(Duration.ofMinutes(5)));
                
                successCount++;
                logger.info("✅ Created GSI index: {}", extractIndexName(processedSql));
                
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    skipCount++;
                    logger.debug("⏭️  GSI index already exists: {}", extractIndexName(processedSql));
                } else {
                    failCount++;
                    logger.error("❌ Failed to create GSI index: {} - {}", 
                        extractIndexName(processedSql), e.getMessage());
                    // Continue with other indexes even if one fails
                }
            }
        }
        
        logger.info("📊 GSI Index Creation Summary: {} created, {} skipped (already exist), {} failed", 
            successCount, skipCount, failCount);
        
        if (failCount > 0) {
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
            ClassPathResource resource = new ClassPathResource(GSI_INDEXES_FILE);
            
            if (!resource.exists()) {
                logger.error("❌ GSI indexes file not found: {}", GSI_INDEXES_FILE);
                return sqlStatements;
            }
            
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
            
        } catch (Exception e) {
            logger.error("❌ Failed to read GSI indexes file: {}", e.getMessage());
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

