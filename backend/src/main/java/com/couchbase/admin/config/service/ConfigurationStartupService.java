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
import java.util.LinkedHashMap;
import java.util.HashMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

/**
 * Service that automatically loads configuration and establishes connections on application startup
 */
@Service
public class ConfigurationStartupService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationStartupService.class);
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml"; // relative to backend working dir (/app)
    private static final String CONFIG_SYS_PROP = "fhir.config";        // -Dfhir.config=/path/to/config.yaml
    private static final String CONFIG_ENV_VAR = "FHIR_CONFIG_FILE";    // environment override
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
     * Expose a retry method for external callers (e.g., REST) to trigger auto-connect again.
     * Returns true if execution attempted (config existed and autoConnect not explicitly false).
     */
    public boolean retryLoadConfigurationAndConnect() {
        try {
            return loadConfigurationAndConnect();
        } catch (Exception e) {
            logger.warn("⚠️ Retry auto-connect failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Load config.yaml and establish connection
     * @return true if an attempt to connect was made (config exists and not disabled)
     */
    public boolean loadConfigurationAndConnect() {
        // Resolve configuration file path with precedence:
        // 1. System property -Dfhir.config
        // 2. Env var FHIR_CONFIG_FILE
        // 3. Default relative ../config.yaml
        String overrideSys = System.getProperty(CONFIG_SYS_PROP);
        String overrideEnv = System.getenv(CONFIG_ENV_VAR);
        String chosenPath = overrideSys != null ? overrideSys : (overrideEnv != null ? overrideEnv : DEFAULT_CONFIG_FILE);

        Path configFile = Paths.get(chosenPath);
        if (!configFile.isAbsolute()) {
            configFile = Paths.get(System.getProperty("user.dir")).resolve(configFile).normalize();
        }

        if (overrideSys != null) {
            logger.info("🔧 Using config override via system property ({}): {}", CONFIG_SYS_PROP, configFile);
        } else if (overrideEnv != null) {
            logger.info("🔧 Using config override via environment variable ({}): {}", CONFIG_ENV_VAR, configFile);
        } else {
            logger.info("🔧 Using default config path: {}", configFile);
        }

        if (!Files.exists(configFile)) {
            logger.info("📋 No config.yaml found - FHIR Server will start without auto-connection");
            return false;
        }

        // Load and parse YAML
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData;
        
        try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
            yamlData = yaml.load(inputStream);
            
            if (yamlData == null) {
                logger.warn("⚠️ config.yaml is empty or invalid");
                return false;
            }
            
            logger.info("✅ Successfully loaded config.yaml with {} top-level keys", yamlData.size());
            
        } catch (Exception e) {
            logger.error("❌ Failed to read config.yaml: {}", e.getMessage());
            return false;
        }

        // Apply logging level overrides if present: logging.levels:
        //   com.couchbase.admin: DEBUG
        //   com.couchbase.fhir: INFO
        @SuppressWarnings("unchecked")
        Map<String, Object> loggingSection = (Map<String, Object>) yamlData.get("logging");
        if (loggingSection != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> levels = (Map<String, Object>) loggingSection.get("levels");
            if (levels != null && !levels.isEmpty()) {
                applyLoggingLevels(levels);
            }
        }

        // Apply Couchbase SDK configuration if present: couchbase.sdk.*
        // This ensures Spring Boot @Value annotations can read these properties
        @SuppressWarnings("unchecked")
        Map<String, Object> couchbaseSection = (Map<String, Object>) yamlData.get("couchbase");
        Map<String, Object> sdkConfig = new HashMap<>();
        if (couchbaseSection != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sdkConfigTemp = (Map<String, Object>) couchbaseSection.get("sdk");
            if (sdkConfigTemp != null) {
                sdkConfig = sdkConfigTemp;
            }
        }
        // Always apply SDK configuration (will use defaults if none specified)
        applyCouchbaseSdkConfiguration(sdkConfig);

        // Extract connection configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> connectionConfig = (Map<String, Object>) yamlData.get("connection");
        
        if (connectionConfig == null) {
            logger.warn("⚠️ No 'connection' section found in config.yaml");
            return false;
        }

        // Extract app configuration and determine autoConnect behavior.
        @SuppressWarnings("unchecked")
        Map<String, Object> appConfig = (Map<String, Object>) yamlData.get("app");
        boolean autoConnectExplicitFalse = appConfig != null && Boolean.FALSE.equals(appConfig.get("autoConnect"));
        boolean autoConnect = !autoConnectExplicitFalse; // default true when connection section present
        if (!autoConnect) {
            logger.info("📋 autoConnect explicitly disabled (app.autoConnect=false) - skipping auto-connection");
            return false;
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
            logger.info("💡 Collections will be warmed up automatically on first access to each FHIR bucket");
        } else {
            String errorMsg = response.getMessage();
            logger.error("❌ Auto-connection failed: {}", errorMsg);
            if (errorMsg != null && (errorMsg.contains("authentication") || 
                                   errorMsg.contains("Authentication") ||
                                   errorMsg.contains("credentials") ||
                                   errorMsg.contains("password"))) {
                logger.error("🔐 AUTHENTICATION FAILURE DETECTED!");
                logger.error("📝 Please check your config.yaml file:");
                logger.error("   - Verify username and password are correct");
                logger.error("   - Ensure Couchbase server credentials match config.yaml");
                logger.error("   - Fix credentials and restart the application");
            } else {
                logger.info("📋 FHIR Server started but no connection established - use frontend or REST API to connect");
            }
        }
        return true;
    }

    /**
     * Apply logging levels supplied in config.yaml under logging.levels
     */
    private void applyLoggingLevels(Map<String, Object> levels) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // Keep insertion order for deterministic logging of what changed
        Map<String, Object> ordered = new LinkedHashMap<>(levels);
        ordered.forEach((k, v) -> {
            if (v == null) return;
            String levelStr = v.toString().trim().toUpperCase();
            try {
                Level lvl = Level.valueOf(levelStr);
                context.getLogger(k).setLevel(lvl);
                logger.info("🪵 Logging level override: {} -> {}", k, lvl);
            } catch (IllegalArgumentException ex) {
                logger.warn("⚠️ Ignoring invalid log level '{}' for logger '{}'. Valid: TRACE, DEBUG, INFO, WARN, ERROR", levelStr, k);
            }
        });
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

    /**
     * Apply Couchbase SDK configuration from config.yaml to Spring Boot system properties
     * This ensures @Value annotations in ConnectionService can read the configured values
     */
    private void applyCouchbaseSdkConfiguration(Map<String, Object> sdkConfig) {
        logger.info("🔧 Effective Couchbase SDK configuration:");
        
        // Define default values and their Spring property mappings
        Map<String, String> defaults = Map.of(
            "transaction-durability", "NONE",
            "query-timeout-seconds", "30",
            "search-timeout-seconds", "30", 
            "kv-timeout-seconds", "10",
            "connect-timeout-seconds", "10",
            "disconnect-timeout-seconds", "10",
            "enable-mutation-tokens", "true",
            "max-http-connections", "128",
            "num-kv-connections", "8"
        );
        
        // Apply configuration (from YAML or defaults) and show what's being used
        defaults.forEach((yamlKey, defaultValue) -> {
            String value = sdkConfig.containsKey(yamlKey) ? String.valueOf(sdkConfig.get(yamlKey)) : defaultValue;
            String source = sdkConfig.containsKey(yamlKey) ? "config.yaml" : "default";
            
            String springProperty = "couchbase.sdk." + yamlKey;
            System.setProperty(springProperty, value);
            
            if (sdkConfig.containsKey(yamlKey)) {
                logger.info("   ✅ {}: {} (from {})", yamlKey, value, source);
            } else {
                logger.info("   🔧 {}: {} ({})", yamlKey, value, source);
            }
        });
        
        // Log any extra properties from YAML that aren't in our defaults
        sdkConfig.forEach((yamlKey, value) -> {
            if (!defaults.containsKey(yamlKey)) {
                String springProperty = "couchbase.sdk." + yamlKey;
                String stringValue = String.valueOf(value);
                System.setProperty(springProperty, stringValue);
                logger.info("   ⚙️  {}: {} (custom from config.yaml)", yamlKey, stringValue);
            }
        });
        
        // Highlight critical transaction durability setting
        String durability = System.getProperty("couchbase.sdk.transaction-durability");
        if ("NONE".equals(durability)) {
            logger.info("🔒 Transaction durability: {} (suitable for development/single-node)", durability);
        } else {
            logger.info("🔒 Transaction durability: {} (production setting - requires replicas)", durability);
        }
    }
}