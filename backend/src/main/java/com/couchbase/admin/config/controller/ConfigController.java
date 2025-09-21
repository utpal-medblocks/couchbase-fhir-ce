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

    public ConfigController(@Autowired(required = false) BuildProperties buildProperties) {
        // Determine version: prefer build-info, then JAR manifest, else dev
        String v = null;
        if (buildProperties != null) {
            try {
                v = buildProperties.getVersion();
            } catch (Exception ignored) { }
        }
        if (v == null) {
            v = getClass().getPackage().getImplementationVersion();
        }
        this.version = v != null ? v : "dev";
        logger.info("Application version resolved to: {}", this.version);
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
     * Version endpoint returning the backend application version.
     */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        Map<String, Object> body = new HashMap<>();
        body.put("version", this.version);
        return ResponseEntity.ok(body);
    }
}