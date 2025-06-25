package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.fhirBucket.model.FhirConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Simple YAML parser for FHIR configuration
 * This is a basic implementation to avoid external YAML dependencies
 */
@Component
public class FhirConfigurationLoader {
    
    public FhirConfiguration loadConfiguration() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/fhir.yml");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            return parseYaml(reader);
        }
    }
    
    private FhirConfiguration parseYaml(BufferedReader reader) throws IOException {
        FhirConfiguration config = new FhirConfiguration();
        FhirConfiguration.FhirSettings fhirSettings = new FhirConfiguration.FhirSettings();
        Map<String, FhirConfiguration.ScopeConfiguration> scopes = new HashMap<>();
        
        String line;
        FhirConfiguration.ScopeConfiguration currentScope = null;
        FhirConfiguration.CollectionConfiguration currentCollection = null;
        FhirConfiguration.IndexConfiguration currentIndex = null;
        boolean inIndexes = false;
        
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            
            // Skip comments and empty lines
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            
            // Stop parsing when we reach build_commands section
            if (trimmed.equals("build_commands:")) {
                break;
            }
            
            // Parse scope level (admin: or resources:)
            if (trimmed.matches("^\\s*(admin|resources):\\s*$")) {
                String scopeKey = trimmed.replace(":", "").trim();
                currentScope = new FhirConfiguration.ScopeConfiguration();
                currentScope.setCollections(new ArrayList<>());
                scopes.put(scopeKey, currentScope);
                inIndexes = false;
                continue;
            }
            
            // Parse scope properties
            if (currentScope != null && trimmed.startsWith("name:") && !inIndexes) {
                currentScope.setName(extractValue(trimmed));
                continue;
            }
            
            if (currentScope != null && trimmed.startsWith("description:") && !inIndexes) {
                currentScope.setDescription(extractValue(trimmed));
                continue;
            }
            
            // Check if we're entering collections section
            if (trimmed.equals("collections:")) {
                inIndexes = false;
                continue;
            }
            
            // Check if we're entering indexes section
            if (trimmed.equals("indexes:")) {
                inIndexes = true;
                continue;
            }
            
            // Parse collection (only if not in indexes section)
            if (currentScope != null && trimmed.startsWith("- name:") && !inIndexes) {
                currentCollection = new FhirConfiguration.CollectionConfiguration();
                currentCollection.setName(extractValue(trimmed));
                currentCollection.setIndexes(new ArrayList<>());
                currentScope.getCollections().add(currentCollection);
                continue;
            }
            
            // Parse collection description
            if (currentCollection != null && trimmed.startsWith("description:") && !inIndexes) {
                currentCollection.setDescription(extractValue(trimmed));
                continue;
            }
            
            // Parse index (only if in indexes section)
            if (currentCollection != null && trimmed.startsWith("- name:") && inIndexes) {
                currentIndex = new FhirConfiguration.IndexConfiguration();
                currentIndex.setName(extractValue(trimmed));
                currentCollection.getIndexes().add(currentIndex);
                continue;
            }
            
            // Parse index properties
            if (currentIndex != null && trimmed.startsWith("type:") && inIndexes) {
                currentIndex.setType(extractValue(trimmed));
                continue;
            }
            
            // Parse index SQL
            if (currentIndex != null && trimmed.startsWith("sql:") && inIndexes) {
                currentIndex.setSql(extractValue(trimmed));
                continue;
            }
        }
        
        fhirSettings.setScopes(scopes);
        config.setFhir(fhirSettings);
        
        return config;
    }
    
    private String extractValue(String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex >= 0 && colonIndex < line.length() - 1) {
            return line.substring(colonIndex + 1).trim().replaceAll("^\"|\"$", "");
        }
        return "";
    }
}
