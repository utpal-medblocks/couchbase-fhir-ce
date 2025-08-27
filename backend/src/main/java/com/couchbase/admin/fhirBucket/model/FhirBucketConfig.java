package com.couchbase.admin.fhirBucket.model;

import java.util.List;

public class FhirBucketConfig {
    private String fhirRelease;
    private List<Profile> profiles;
    private Validation validation;
    private Logs logs;

    public String getFhirRelease() {
        return fhirRelease;
    }
    public void setFhirRelease(String fhirRelease) {
        this.fhirRelease = fhirRelease;
    }
    public List<Profile> getProfiles() {
        return profiles;
    }
    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles;
    }
    public Validation getValidation() {
        return validation;
    }
    public void setValidation(Validation validation) {
        this.validation = validation;
    }
    public Logs getLogs() {
        return logs;
    }
    public void setLogs(Logs logs) {
        this.logs = logs;
    }

    public static class Profile {
        private String profile;
        private String version;
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    public static class Validation {
        private String mode;    // "strict" | "lenient" | "disabled"
        private String profile; // "none" | "us-core"
        
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        
        // Convenience methods for backward compatibility and validation logic
        public boolean isEnforceUSCore() { return "us-core".equals(profile); }
        public boolean isStrictValidation() { return "strict".equals(mode); }
        public boolean isLenientValidation() { return "lenient".equals(mode); }
        public boolean isValidationDisabled() { return "disabled".equals(mode); }
    }

    public static class Logs {
        private boolean enableSystem;
        private boolean enableCRUDAudit;
        private boolean enableSearchAudit;
        private String rotationBy;
        private int number;
        private String s3Endpoint;
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
}
