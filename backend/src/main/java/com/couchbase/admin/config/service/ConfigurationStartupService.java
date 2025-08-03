package com.couchbase.admin.config.service;

import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Service that automatically loads configuration and establishes connections on application startup
 */
@Service
public class ConfigurationStartupService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationStartupService.class);
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml";
    private static final String DEFAULT_CONNECTION_NAME = "default";

    @Autowired
    private ConnectionService connectionService;

    /**
     * Load configuration and establish connection after application is fully started
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("🔧 Application ready - attempting to load startup configuration");
        
        try {
            loadConfigurationAndConnect();
        } catch (Exception e) {
            logger.warn("⚠️ Failed to load startup configuration: {}", e.getMessage());
            logger.info("📋 FHIR Server will start without auto-connection - use frontend or REST API to establish connection");
        }
    }

    /**
     * Load config.yaml and establish connection
     */
    private void loadConfigurationAndConnect() {
        // Try to load YAML config
        Path configFile = Paths.get(DEFAULT_CONFIG_FILE);
        if (!configFile.isAbsolute()) {
            configFile = Paths.get(System.getProperty("user.dir")).resolve(DEFAULT_CONFIG_FILE);
        }

        logger.info("🔧 Looking for configuration file: {}", configFile.toAbsolutePath());

        if (!Files.exists(configFile)) {
            logger.info("📋 No config.yaml found - FHIR Server will start without auto-connection");
            return;
        }

        // Load and parse YAML
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData;
        
        try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
            yamlData = yaml.load(inputStream);
            
            if (yamlData == null) {
                logger.warn("⚠️ config.yaml is empty or invalid");
                return;
            }
            
            logger.info("✅ Successfully loaded config.yaml with {} top-level keys", yamlData.size());
            
        } catch (Exception e) {
            logger.error("❌ Failed to read config.yaml: {}", e.getMessage(), e);
            return;
        }

        // Extract connection configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> connectionConfig = (Map<String, Object>) yamlData.get("connection");
        
        if (connectionConfig == null) {
            logger.warn("⚠️ No 'connection' section found in config.yaml");
            return;
        }

        // Extract app configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> appConfig = (Map<String, Object>) yamlData.get("app");
        boolean autoConnect = appConfig != null && Boolean.TRUE.equals(appConfig.get("autoConnect"));
        
        if (!autoConnect) {
            logger.info("📋 autoConnect is disabled in config.yaml - skipping auto-connection");
            return;
        }

        // Create connection request from YAML data
        ConnectionRequest request = createConnectionRequest(connectionConfig);
        
        logger.info("🔗 Attempting auto-connection to: {} ({})", 
                   request.getConnectionString(), request.getServerType());

        // Establish connection
        ConnectionResponse response = connectionService.createConnection(request);
        
        if (response.isSuccess()) {
            logger.info("✅ Auto-connection successful! FHIR Server is ready for API requests");
            logger.info("🚀 Backend startup complete - FHIR APIs are now available");
        } else {
            String errorMsg = response.getMessage();
            logger.error("❌ Auto-connection failed: {}", errorMsg);
            
            // Check for authentication failures
            if (errorMsg != null && (errorMsg.contains("authentication") || 
                                   errorMsg.contains("Authentication") ||
                                   errorMsg.contains("credentials") ||
                                   errorMsg.contains("password"))) {
                logger.error("🔐 AUTHENTICATION FAILURE DETECTED!");
                logger.error("📝 Please check your config.yaml file:");
                logger.error("   - Verify username and password are correct");
                logger.error("   - Ensure Couchbase server credentials match config.yaml");
                logger.error("   - Fix credentials and restart the application");
                logger.error("⚠️  Backend will not retry automatically - manual restart required after fixing config.yaml");
            } else {
                logger.info("📋 FHIR Server started but no connection established - use frontend or REST API to connect");
            }
        }
    }

    /**
     * Create ConnectionRequest from YAML configuration
     */
    private ConnectionRequest createConnectionRequest(Map<String, Object> connectionConfig) {
        ConnectionRequest request = new ConnectionRequest();
        
        // Apply same localhost -> 127.0.0.1 transformation as frontend
        String connectionString = (String) connectionConfig.get("connectionString");
        String safeConnectionString = connectionString.replaceAll("localhost(?![\\w.])", "127.0.0.1");
        
        logger.debug("🔧 Connection string transformation: {} -> {}", connectionString, safeConnectionString);
        
        request.setName(DEFAULT_CONNECTION_NAME);
        request.setConnectionString(safeConnectionString);
        request.setUsername((String) connectionConfig.get("username"));
        request.setPassword((String) connectionConfig.get("password"));
        request.setServerType((String) connectionConfig.get("serverType"));
        request.setSslEnabled(Boolean.TRUE.equals(connectionConfig.get("sslEnabled")));
        
        // Set default bucket/scope/collection if not specified
        request.setBucket("fhir");        // Default FHIR bucket name
        request.setScope("Resources");    // Default FHIR scope name  
        request.setCollection("_default"); // Default collection name
        
        return request;
    }
}