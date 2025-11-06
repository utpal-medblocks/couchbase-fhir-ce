package com.couchbase.fhir.resources.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.bundle.fastpath")
public class BundleFastpathProperties {
    
    private boolean enabled = true;
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
