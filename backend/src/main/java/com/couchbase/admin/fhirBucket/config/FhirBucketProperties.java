package com.couchbase.admin.fhirBucket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Spring Boot Configuration Properties for FHIR bucket configuration
 * Maps directly from the YAML file using @ConfigurationProperties
 */
@Component
@ConfigurationProperties(prefix = "fhir")
public class FhirBucketProperties {
    
    private Map<String, ScopeConfiguration> scopes;
    private List<BuildCommand> buildCommands;
    
    // Getters and Setters
    public Map<String, ScopeConfiguration> getScopes() {
        return scopes;
    }
    
    public void setScopes(Map<String, ScopeConfiguration> scopes) {
        this.scopes = scopes;
    }
    
    public List<BuildCommand> getBuildCommands() {
        return buildCommands;
    }
    
    public void setBuildCommands(List<BuildCommand> buildCommands) {
        this.buildCommands = buildCommands;
    }
    
    public static class ScopeConfiguration {
        private String name;
        private String description;
        private List<CollectionConfiguration> collections;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public List<CollectionConfiguration> getCollections() {
            return collections;
        }
        
        public void setCollections(List<CollectionConfiguration> collections) {
            this.collections = collections;
        }
    }
    
    public static class CollectionConfiguration {
        private String name;
        private String description;
        private List<IndexConfiguration> indexes;
        private Integer maxTtlSeconds; // Optional: max TTL for documents in this collection
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public List<IndexConfiguration> getIndexes() {
            return indexes;
        }
        
        public void setIndexes(List<IndexConfiguration> indexes) {
            this.indexes = indexes;
        }
        
        public Integer getMaxTtlSeconds() {
            return maxTtlSeconds;
        }
        
        public void setMaxTtlSeconds(Integer maxTtlSeconds) {
            this.maxTtlSeconds = maxTtlSeconds;
        }
    }
    
    public static class IndexConfiguration {
        private String name;
        private String type;
        private String sql;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getSql() {
            return sql;
        }
        
        public void setSql(String sql) {
            this.sql = sql;
        }
    }
    
    public static class BuildCommand {
        private String name;
        private String description;
        private String query;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public String getQuery() {
            return query;
        }
        
        public void setQuery(String query) {
            this.query = query;
        }
    }
} 