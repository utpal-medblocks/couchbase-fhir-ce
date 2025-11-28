package com.couchbase.admin.config.service;

import com.couchbase.admin.connections.model.ConnectionRequest;
import com.couchbase.admin.connections.model.ConnectionResponse;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.initialization.model.InitializationStatus;
import com.couchbase.admin.initialization.service.InitializationService;
import com.couchbase.admin.tokens.service.JwtTokenCacheService;
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
    
    @Autowired
    private InitializationService initializationService;
    
    @Autowired
    private JwtTokenCacheService jwtTokenCacheService;
    
    @Autowired
    private com.couchbase.fhir.auth.AuthorizationServerConfig authorizationServerConfig;

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
        
        // Extract app.baseUrl and set as system property for FhirServerConfig to use
        if (appConfig != null && appConfig.get("baseUrl") != null) {
            String baseUrl = String.valueOf(appConfig.get("baseUrl"));
            System.setProperty("app.baseUrl", baseUrl);
            logger.info("📋 FHIR Server base URL: {}", baseUrl);
        }

        // Extract admin credentials and set as system properties for AdminConfig to use
        @SuppressWarnings("unchecked")
        Map<String, Object> adminConfig = (Map<String, Object>) yamlData.get("admin");
        if (adminConfig != null) {
            if (adminConfig.get("email") != null) {
                System.setProperty("admin.email", String.valueOf(adminConfig.get("email")));
            }
            if (adminConfig.get("password") != null) {
                System.setProperty("admin.password", String.valueOf(adminConfig.get("password")));
            }
            if (adminConfig.get("name") != null) {
                System.setProperty("admin.name", String.valueOf(adminConfig.get("name")));
            }
            logger.info("🔐 Admin UI credentials loaded from config.yaml");
        }

        // Extract CORS configuration for diagnostics (bean already created earlier with initial values)
        @SuppressWarnings("unchecked")
        Map<String, Object> corsConfig = (Map<String, Object>) yamlData.get("cors");
        if (corsConfig != null) {
            Object origins = corsConfig.getOrDefault("allowed-origins", "(default) http://localhost:5173");
            Object methods = corsConfig.getOrDefault("allowed-methods", "GET,POST,PUT,DELETE,OPTIONS");
            Object headers = corsConfig.getOrDefault("allowed-headers", "*");
            logger.info("🌐 CORS configuration (from config.yaml):");
            logger.info("   Origins: {}", origins);
            logger.info("   Methods: {}", methods);
            logger.info("   Headers: {}", headers);
            if (String.valueOf(origins).contains("*") && "true".equalsIgnoreCase(System.getProperty("cors.credentials.allowed", "true"))) {
                logger.info("   ⚠️ Wildcard origin detected with credentials allowed; Spring will use allowedOriginPatterns");
            }
        } else {
            logger.info("🌐 No 'cors' section found in config.yaml - using default origin http://localhost:5173");
        }

        // Create connection request from YAML data
        ConnectionRequest request = createConnectionRequest(connectionConfig);
        
        logger.info("🔗 Attempting auto-connection to: {} ({})", 
                   request.getConnectionString(), request.getServerType());

        // Establish connection
        ConnectionResponse response = connectionService.createConnection(request);
        
        if (response.isSuccess()) {
            logger.info("✅ Auto-connection successful!");
            
            // Initialize OAuth signing key after connection is established
            initializeOAuthSigningKey();
            
            // Initialize JWT token cache after connection is established
            initializeTokenCache();
            
            // Check initialization status for single-tenant "fhir" bucket
            checkAndReportInitializationStatus();
            
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
     * Check and report FHIR bucket initialization status
     * Single-tenant mode: expects exactly one bucket named "fhir"
     */
    private void checkAndReportInitializationStatus() {
        try {
            InitializationStatus status = initializationService.checkStatus(DEFAULT_CONNECTION_NAME);
            
            logger.info("╔══════════════════════════════════════════════════════════════╗");
            logger.info("║         FHIR SYSTEM INITIALIZATION STATUS                    ║");
            logger.info("╚══════════════════════════════════════════════════════════════╝");
            logger.info("📊 Status: {}", status.getStatus());
            logger.info("📦 Bucket: {}", status.getBucketName());
            logger.info("🔗 Connection: {}", status.isHasConnection() ? "✅ Connected" : "❌ Not Connected");
            logger.info("🪣 Bucket Exists: {}", status.isBucketExists() ? "✅ Yes" : "❌ No");
            logger.info("⚙️  FHIR Initialized: {}", status.isFhirInitialized() ? "✅ Yes" : "❌ No");
            logger.info("─────────────────────────────────────────────────────────────");
            
            switch (status.getStatus()) {
                case READY:
                    logger.info("✅ {}", status.getMessage());
                    logger.info("🚀 Backend startup complete - FHIR APIs are now available");
                    logger.info("💡 Collections will be warmed up automatically on first access");
                    break;
                    
                case BUCKET_MISSING:
                    logger.warn("⚠️  {}", status.getMessage());
                    logger.warn("📋 NEXT STEPS:");
                    logger.warn("   1. Create bucket '{}' in Couchbase UI or CLI", status.getBucketName());
                    logger.warn("   2. Use Admin UI to initialize FHIR configuration");
                    break;
                    
                case BUCKET_NOT_INITIALIZED:
                    logger.warn("⚠️  {}", status.getMessage());
                    logger.warn("📋 NEXT STEPS:");
                    logger.warn("   1. Open Admin UI in your browser");
                    logger.warn("   2. Click 'Initialize FHIR Bucket' to set up scopes/collections/indexes");
                    break;
                    
                case NOT_CONNECTED:
                    logger.error("❌ {}", status.getMessage());
                    break;
            }
            
            logger.info("╚══════════════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            logger.error("❌ Failed to check initialization status: {}", e.getMessage());
        }
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
    
    /**
     * Initialize the OAuth signing key after Couchbase connection is established
     * This must run AFTER the connection is established, before any login attempts
     */
    private void initializeOAuthSigningKey() {
        try {
            logger.info("🔐 Initializing OAuth signing key...");
            authorizationServerConfig.initializeSigningKey();
        } catch (Exception e) {
            logger.error("❌ Failed to initialize OAuth signing key: {}", e.getMessage());
            throw new IllegalStateException("Cannot start without OAuth signing key", e);
        }
    }
    
    /**
     * Initialize the JWT token cache after Couchbase connection is established
     */
    private void initializeTokenCache() {
        try {
            logger.info("🔐 Loading active JWT tokens into cache...");
            jwtTokenCacheService.loadActiveTokens();
            
            if (jwtTokenCacheService.isInitialized()) {
                int cacheSize = jwtTokenCacheService.getCacheSize();
                logger.info("✅ Token cache initialized with {} active tokens", cacheSize);
            } else {
                logger.warn("⏭️ Token cache not initialized (FHIR bucket may not be initialized yet)");
            }
            
        } catch (Exception e) {
            logger.warn("⚠️ Failed to initialize token cache: {}", e.getMessage());
            logger.debug("Token cache initialization error:", e);
            // Don't fail startup if cache initialization fails
        }
    }
}