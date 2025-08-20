package com.couchbase.fhir.resources.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

/**
 * Service to retrieve and manage FHIR bucket configuration settings.
 * Handles validation modes, audit settings, and other bucket-specific FHIR configurations.
 */
@Service
public class FhirBucketConfigService {
    
    private static final Logger logger = LoggerFactory.getLogger(FhirBucketConfigService.class);
    
    @Autowired
    private ConnectionService connectionService;
    
    /**
     * Profile information class
     */
    public static class ProfileInfo {
        private String profile;
        private String version;
        
        public ProfileInfo() {}
        
        public ProfileInfo(String profile, String version) {
            this.profile = profile;
            this.version = version;
        }
        
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
    
    /**
     * Logs configuration class
     */
    public static class LogsConfig {
        private boolean enableSystem = false;
        private boolean enableCRUDAudit = false;
        private boolean enableSearchAudit = false;
        private String rotationBy = "size";  // "size" | "days"
        private int number = 30;             // in GB or Days
        private String s3Endpoint = "";
        
        // Getters and setters
        public boolean isEnableSystem() { return enableSystem; }
        public void setEnableSystem(boolean enableSystem) { this.enableSystem = enableSystem; }
        
        public boolean isEnableCRUDAudit() { return enableCRUDAudit; }
        public void setEnableCRUDAudit(boolean enableCRUDAudit) { this.enableCRUDAudit = enableCRUDAudit; }
        
        public boolean isEnableSearchAudit() { return enableSearchAudit; }
        public void setEnableSearchAudit(boolean enableSearchAudit) { this.enableSearchAudit = enableSearchAudit; }
        
        public String getRotationBy() { return rotationBy; }
        public void setRotationBy(String rotationBy) { this.rotationBy = rotationBy; }
        
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        
        public String getS3Endpoint() { return s3Endpoint; }
        public void setS3Endpoint(String s3Endpoint) { this.s3Endpoint = s3Endpoint; }
    }

    /**
     * Configuration class for FHIR bucket settings
     */
    public static class FhirBucketConfig {
        private String fhirRelease = "Release 4";
        private List<ProfileInfo> profiles = new ArrayList<>();
        private String validationMode = "lenient";      // "strict" | "lenient" | "disabled"
        private boolean enforceUSCore = false;          // true = strict US Core validation
        private boolean allowUnknownElements = true;    // true = strip unknown fields
        private boolean terminologyChecks = false;      // true = validate terminologies
        private LogsConfig logs = new LogsConfig();
        
        // Getters and setters
        public String getValidationMode() { return validationMode; }
        public void setValidationMode(String validationMode) { this.validationMode = validationMode; }
        
        public boolean isEnforceUSCore() { return enforceUSCore; }
        public void setEnforceUSCore(boolean enforceUSCore) { this.enforceUSCore = enforceUSCore; }
        
        public boolean isAllowUnknownElements() { return allowUnknownElements; }
        public void setAllowUnknownElements(boolean allowUnknownElements) { this.allowUnknownElements = allowUnknownElements; }
        
        public boolean isTerminologyChecks() { return terminologyChecks; }
        public void setTerminologyChecks(boolean terminologyChecks) { this.terminologyChecks = terminologyChecks; }
        
        public String getFhirRelease() { return fhirRelease; }
        public void setFhirRelease(String fhirRelease) { this.fhirRelease = fhirRelease; }
        
        public List<ProfileInfo> getProfiles() { return profiles; }
        public void setProfiles(List<ProfileInfo> profiles) { this.profiles = profiles; }
        
        public LogsConfig getLogs() { return logs; }
        public void setLogs(LogsConfig logs) { this.logs = logs; }
        
        // Convenience methods
        public boolean isStrictValidation() {
            return "strict".equalsIgnoreCase(validationMode);
        }
        
        public boolean isLenientValidation() {
            return "lenient".equalsIgnoreCase(validationMode);
        }
        
        public boolean isValidationDisabled() {
            return "disabled".equalsIgnoreCase(validationMode);
        }
    }
    
    /**
     * Get FHIR configuration for a specific bucket
     */
    public FhirBucketConfig getFhirBucketConfig(String bucketName) {
        return getFhirBucketConfig(bucketName, getDefaultConnection());
    }
    
    /**
     * Get FHIR configuration for a specific bucket and connection
     */
    public FhirBucketConfig getFhirBucketConfig(String bucketName, String connectionName) {
        try {
            Cluster cluster = connectionService.getConnection(connectionName);
            if (cluster == null) {
                logger.warn("No active connection found: {}, using default config", connectionName);
                return getDefaultConfig();
            }
            
            // Query the FHIR config document
            String query = String.format(
                "SELECT c.* FROM `%s`.`Admin`.`config` c USE KEYS 'fhir-config'",
                bucketName
            );
            
            QueryResult result = cluster.query(query);
            List<JsonObject> rows = result.rowsAsObject();
            
            if (rows.isEmpty()) {
                logger.warn("No FHIR config found for bucket: {}, using default config", bucketName);
                return getDefaultConfig();
            }
            
            JsonObject configDoc = rows.get(0);
            return parseConfigDocument(configDoc);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve FHIR config for bucket: {}, error: {}", bucketName, e.getMessage());
            return getDefaultConfig();
        }
    }
    
    /**
     * Parse the configuration document into a FhirBucketConfig object
     */
    private FhirBucketConfig parseConfigDocument(JsonObject configDoc) {
        FhirBucketConfig config = new FhirBucketConfig();
        
        try {
            // Parse basic FHIR info
            String fhirRelease = configDoc.getString("fhirRelease");
            if (fhirRelease != null) {
                config.setFhirRelease(fhirRelease);
            }
            
            // Parse profiles
            com.couchbase.client.java.json.JsonArray profiles = configDoc.getArray("profiles");
            if (profiles != null) {
                List<ProfileInfo> profileList = new ArrayList<>();
                for (int i = 0; i < profiles.size(); i++) {
                    JsonObject profileObj = profiles.getObject(i);
                    if (profileObj != null) {
                        ProfileInfo profileInfo = new ProfileInfo(
                            profileObj.getString("profile"),
                            profileObj.getString("version")
                        );
                        profileList.add(profileInfo);
                    }
                }
                config.setProfiles(profileList);
            }
            
            // Parse validation settings
            JsonObject validation = configDoc.getObject("validation");
            if (validation != null) {
                config.setValidationMode(validation.getString("mode"));
                config.setEnforceUSCore(validation.getBoolean("enforceUSCore"));
                config.setAllowUnknownElements(validation.getBoolean("allowUnknownElements"));
                config.setTerminologyChecks(validation.getBoolean("terminologyChecks"));
            }
            
            // Parse logs settings
            JsonObject logs = configDoc.getObject("logs");
            if (logs != null) {
                LogsConfig logsConfig = new LogsConfig();
                logsConfig.setEnableSystem(logs.getBoolean("enableSystem"));
                logsConfig.setEnableCRUDAudit(logs.getBoolean("enableCRUDAudit"));
                logsConfig.setEnableSearchAudit(logs.getBoolean("enableSearchAudit"));
                logsConfig.setRotationBy(logs.getString("rotationBy"));
                logsConfig.setNumber(logs.getInt("number"));
                logsConfig.setS3Endpoint(logs.getString("s3Endpoint"));
                config.setLogs(logsConfig);
            }
            
            logger.debug("Loaded FHIR config: release={}, validation={}, profiles={}", 
                config.getFhirRelease(), config.getValidationMode(), config.getProfiles().size());
                
        } catch (Exception e) {
            logger.warn("Failed to parse FHIR config document, using defaults: {}", e.getMessage());
            return getDefaultConfig();
        }
        
        return config;
    }
    
    /**
     * Get default configuration (lenient validation)
     */
    private FhirBucketConfig getDefaultConfig() {
        FhirBucketConfig config = new FhirBucketConfig();
        
        // Set defaults
        config.setFhirRelease("Release 4");
        
        // Default US Core profile
        List<ProfileInfo> defaultProfiles = new ArrayList<>();
        defaultProfiles.add(new ProfileInfo("US Core", "6.1.0"));
        config.setProfiles(defaultProfiles);
        
        // Default validation settings
        config.setValidationMode("lenient");
        config.setEnforceUSCore(false);
        config.setAllowUnknownElements(true);
        config.setTerminologyChecks(false);
        
        // Default logs settings
        LogsConfig defaultLogs = new LogsConfig();
        config.setLogs(defaultLogs);
        
        return config;
    }
    
    /**
     * Get default connection name
     */
    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? "default" : connections.get(0);
    }
}
