package com.couchbase.common.config;

import org.springframework.stereotype.Component;

/**
 * Configuration properties for Admin credentials
 * Reads from system properties set by ConfigurationStartupService from config.yaml
 */
@Component
public class AdminConfig {
    
    public String getEmail() {
        String email = System.getProperty("admin.email");
        return email != null ? email : "admin@couchbase.com";
    }
    
    public String getPassword() {
        String password = System.getProperty("admin.password");
        return password != null ? password : "Admin123!";
    }
    
    public String getName() {
        String name = System.getProperty("admin.name");
        return name != null ? name : "Admin";
    }
}

