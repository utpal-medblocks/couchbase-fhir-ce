package com.couchbase.admin.config.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus; 
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;

/**
 * REST controller for handling configuration file operations
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class ConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigController.class);
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml"; // Backend runs from backend/ subdirectory
    private final String version;
    private final String buildTime;
    private final BuildProperties buildProperties;

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

    /**
     * Return a sanitized summary of the config useful for the UI when startup fails
     * Does not expose secrets (password is masked).
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getConfigSummary(@RequestParam(required = false) String path) {
        Map<String, Object> response = new HashMap<>();

        try {
            String configPath = path != null ? path : DEFAULT_CONFIG_FILE;
            Path configFile = Paths.get(configPath);
            if (!configFile.isAbsolute()) {
                configFile = Paths.get(System.getProperty("user.dir")).resolve(configPath);
            }

            boolean exists = Files.exists(configFile);
            response.put("configPath", configFile.toAbsolutePath().toString());
            response.put("configExists", exists);

            if (!exists) {
                response.put("success", true);
                response.put("message", "Config file not found");
                return ResponseEntity.ok(response);
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                Map<String, Object> yamlData = yaml.load(inputStream);
                if (yamlData == null) {
                    response.put("success", true);
                    response.put("message", "Empty or invalid YAML file");
                    return ResponseEntity.ok(response);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> connection = (Map<String, Object>) yamlData.get("connection");
                @SuppressWarnings("unchecked")
                Map<String, Object> app = (Map<String, Object>) yamlData.get("app");

                Map<String, Object> connectionSummary = new HashMap<>();
                if (connection != null) {
                    Object connectionString = connection.get("connectionString");
                    Object username = connection.get("username");
                    Object password = connection.get("password");
                    Object serverType = connection.get("serverType");
                    Object sslEnabled = connection.get("sslEnabled");

                    // Mask password fully, reveal length only
                    String maskedPassword = null;
                    if (password instanceof String) {
                        int len = ((String) password).length();
                        maskedPassword = len > 0 ? "*".repeat(Math.min(len, 12)) : ""; // cap mask to 12
                    }

                    connectionSummary.put("server", connectionString);
                    connectionSummary.put("username", username);
                    connectionSummary.put("passwordMasked", maskedPassword);
                    connectionSummary.put("serverType", serverType);
                    connectionSummary.put("sslEnabled", sslEnabled);
                }

                Map<String, Object> appSummary = new HashMap<>();
                if (app != null) {
                    appSummary.put("autoConnect", app.get("autoConnect"));
                    appSummary.put("showConnectionDialog", app.get("showConnectionDialog"));
                }

                response.put("success", true);
                response.put("connection", connectionSummary);
                response.put("app", appSummary);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            logger.error("Failed to prepare config summary: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Load and return YAML configuration file
     */
    @GetMapping("/yaml")
    public ResponseEntity<Map<String, Object>> getYamlConfig(@RequestParam(required = false) String path) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Use provided path or default to config.yaml in project root
            String configPath = path != null ? path : DEFAULT_CONFIG_FILE;
            Path configFile = Paths.get(configPath);
            
            // If relative path, resolve from project root
            if (!configFile.isAbsolute()) {
                configFile = Paths.get(System.getProperty("user.dir")).resolve(configPath);
            }
            
            logger.info("🔧 Attempting to load YAML config from: {}", configFile.toAbsolutePath());
            
            if (!Files.exists(configFile)) {
                logger.warn("⚠️ Config file not found: {}", configFile.toAbsolutePath());
                response.put("success", false);
                response.put("error", "Config file not found: " + configPath);
                return ResponseEntity.ok(response);
            }
            
            // Load and parse YAML file
            Yaml yaml = new Yaml();
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                Map<String, Object> yamlData = yaml.load(inputStream);
                
                if (yamlData == null) {
                    logger.warn("⚠️ Empty or invalid YAML file: {}", configFile.toAbsolutePath());
                    response.put("success", false);
                    response.put("error", "Empty or invalid YAML file");
                    return ResponseEntity.ok(response);
                }
                
                logger.info("✅ Successfully loaded YAML config with {} top-level keys", yamlData.size());
                
                response.put("success", true);
                response.put("config", yamlData);
                response.put("configPath", configFile.toAbsolutePath().toString());
                
                return ResponseEntity.ok(response);
                
            }
            
        } catch (Exception e) {
            logger.error("❌ Failed to load YAML config: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to load config: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
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