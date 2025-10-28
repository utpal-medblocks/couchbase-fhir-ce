package com.couchbase.admin.config.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;

import com.couchbase.admin.config.service.ConfigurationStartupService;

/**
 * REST controller for handling configuration file operations
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class ConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigController.class);
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml"; // Backend runs from backend/ subdirectory
    private static final String CONFIG_SYS_PROP = "fhir.config";
    private static final String CONFIG_ENV_VAR = "FHIR_CONFIG_FILE";
    private final String version;
    private final String buildTime;
    private final BuildProperties buildProperties;

    @Autowired(required = false)
    private ConfigurationStartupService configurationStartupService;

    public ConfigController(@Autowired(required = false) BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
        
        // Determine version: prefer build-info, then JAR manifest, else dev
        String v = null;
        String bt = null;
        if (buildProperties != null) {
            try {
                v = buildProperties.getVersion();
                bt = buildProperties.getTime() != null 
                    ? buildProperties.getTime().toString() 
                    : null;
            } catch (Exception ignored) { }
        }
        if (v == null) {
            v = getClass().getPackage().getImplementationVersion();
        }
        this.version = v != null ? v : "dev";
        this.buildTime = bt;
        logger.info("Application version resolved to: {}, build time: {}", this.version, this.buildTime);
    }

    private Path resolveConfigPath(String overridePath) {
        String overrideSys = System.getProperty(CONFIG_SYS_PROP);
        String overrideEnv = System.getenv(CONFIG_ENV_VAR);
        String chosenPath = overridePath != null ? overridePath : (overrideSys != null ? overrideSys : (overrideEnv != null ? overrideEnv : DEFAULT_CONFIG_FILE));
        Path configFile = Paths.get(chosenPath);
        if (!configFile.isAbsolute()) {
            configFile = Paths.get(System.getProperty("user.dir")).resolve(configFile).normalize();
        }
        return configFile;
    }

    /**
     * Manually retry auto-connection from config.yaml (useful when Couchbase starts later)
     */
    @PostMapping("/retry-auto-connect")
    public ResponseEntity<Map<String, Object>> retryAutoConnect() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (configurationStartupService == null) {
                response.put("success", false);
                response.put("error", "Configuration service not available");
                return ResponseEntity.ok(response);
            }
            boolean started = configurationStartupService.retryLoadConfigurationAndConnect();
            response.put("success", started);
            response.put("message", started ? "Auto-connect initiated" : "Auto-connect skipped (no config or disabled)" );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to retry auto-connect: {}", e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Return a sanitized summary of the config useful for the UI when startup fails
     * Does not expose secrets (password is masked).
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getConfigSummary(@RequestParam(required = false) String path) {
        Map<String, Object> response = new HashMap<>();

        try {
            Path configFile = resolveConfigPath(path);
            boolean exists = Files.exists(configFile);
            response.put("configPath", configFile.toAbsolutePath().toString());
            response.put("configExists", exists);

            if (!exists) {
                response.put("success", false);
                response.put("message", "Config file not found");
                return ResponseEntity.ok(response);
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                try {
                    Map<String, Object> yamlData = yaml.load(inputStream);
                    if (yamlData == null) {
                        response.put("success", false);
                        response.put("message", "Empty or invalid YAML file (check indentation and structure)");
                        return ResponseEntity.ok(response);
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> connection = (Map<String, Object>) yamlData.get("connection");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> app = (Map<String, Object>) yamlData.get("app");

                    // Validate required structure
                    if (connection == null) {
                        response.put("success", false);
                        response.put("message", "Misconfigured config.yaml - missing 'connection' section (check indentation)");
                        return ResponseEntity.ok(response);
                    }
                    Object connectionString = connection.get("connectionString");
                    Object username = connection.get("username");
                    Object password = connection.get("password");
                    if (!(connectionString instanceof String) || ((String) connectionString).isBlank() ||
                        !(username instanceof String) || ((String) username).isBlank() ||
                        !(password instanceof String)) {
                        response.put("success", false);
                        response.put("message", "Misconfigured config.yaml - 'connectionString', 'username', and 'password' are required");
                        return ResponseEntity.ok(response);
                    }

                    Object serverType = connection.get("serverType");
                    Object sslEnabled = connection.get("sslEnabled");

                    String maskedPassword = null;
                    if (password instanceof String) {
                        int len = ((String) password).length();
                        maskedPassword = len > 0 ? "*".repeat(Math.min(len, 12)) : "";
                    }

                    Map<String, Object> connectionSummary = new HashMap<>();
                    connectionSummary.put("server", connectionString);
                    connectionSummary.put("username", username);
                    connectionSummary.put("passwordMasked", maskedPassword);
                    connectionSummary.put("serverType", serverType);
                    connectionSummary.put("sslEnabled", sslEnabled);

                    Map<String, Object> appSummary = new HashMap<>();
                    if (app != null) {
                        appSummary.put("autoConnect", app.get("autoConnect"));
                        appSummary.put("showConnectionDialog", app.get("showConnectionDialog"));
                    }

                    response.put("success", true);
                    response.put("connection", connectionSummary);
                    response.put("app", appSummary);
                    return ResponseEntity.ok(response);
                } catch (YAMLException ye) {
                    response.put("success", false);
                    response.put("message", "Malformed YAML - check indentation and structure");
                    response.put("yamlError", ye.getMessage());
                    return ResponseEntity.ok(response);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to prepare config summary: {}", e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Load and return YAML configuration file
     */
    @GetMapping("/yaml")
    public ResponseEntity<Map<String, Object>> getYamlConfig(@RequestParam(required = false) String path) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Path configFile = resolveConfigPath(path);
            
            logger.info("🔧 Attempting to load YAML config from: {}", configFile.toAbsolutePath());
            
            if (!Files.exists(configFile)) {
                logger.warn("⚠️ Config file not found: {}", configFile.toAbsolutePath());
                response.put("success", false);
                response.put("error", "Config file not found: " + configFile.toAbsolutePath());
                return ResponseEntity.ok(response);
            }
            
            Yaml yaml = new Yaml();
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                try {
                    Map<String, Object> yamlData = yaml.load(inputStream);
                    
                    if (yamlData == null) {
                        logger.warn("⚠️ Empty or invalid YAML file: {}", configFile.toAbsolutePath());
                        response.put("success", false);
                        response.put("error", "Empty or invalid YAML file (check indentation and structure)");
                        return ResponseEntity.ok(response);
                    }
                    
                    logger.info("✅ Successfully loaded YAML config with {} top-level keys", yamlData.size());
                    
                    response.put("success", true);
                    response.put("config", yamlData);
                    response.put("configPath", configFile.toAbsolutePath().toString());
                    
                    return ResponseEntity.ok(response);
                    
                } catch (YAMLException ye) {
                    logger.warn("❌ YAML parse error for {}: {}", configFile.toAbsolutePath(), ye.getMessage());
                    response.put("success", false);
                    response.put("error", "Malformed YAML - check indentation and structure");
                    response.put("yamlError", ye.getMessage());
                    response.put("configPath", configFile.toAbsolutePath().toString());
                    return ResponseEntity.ok(response);
                }
                
            }
            
        } catch (Exception e) {
            logger.error("❌ Failed to load YAML config: {}", e.getMessage());
            response.put("success", false);
            response.put("error", "Failed to load config: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Health check endpoint for config service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "ConfigController");
        return ResponseEntity.ok(response);
    }

    /**
     * Version endpoint returning the backend application version and build info.
     */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        Map<String, Object> body = new HashMap<>();
        body.put("version", this.version);
        
        if (buildProperties != null) {
            if (buildTime != null) {
                body.put("buildTime", buildTime);
            }
            try {
                if (buildProperties.getName() != null) {
                    body.put("name", buildProperties.getName());
                }
                if (buildProperties.getGroup() != null) {
                    body.put("group", buildProperties.getGroup());
                }
                if (buildProperties.getArtifact() != null) {
                    body.put("artifact", buildProperties.getArtifact());
                }
            } catch (Exception ignored) { }
        }
        
        return ResponseEntity.ok(body);
    }
}