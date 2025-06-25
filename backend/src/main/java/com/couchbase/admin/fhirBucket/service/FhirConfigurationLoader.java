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
        List<FhirConfiguration.BuildCommand> buildCommands = new ArrayList<>();
        
        String line;
        FhirConfiguration.ScopeConfiguration currentScope = null;
        FhirConfiguration.CollectionConfiguration currentCollection = null;
        FhirConfiguration.IndexConfiguration currentIndex = null;
        FhirConfiguration.BuildCommand currentBuildCommand = null;
        boolean inIndexes = false;
        boolean inBuildCommands = false;
        boolean inQuery = false;
        StringBuilder queryBuilder = new StringBuilder();
        
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            
            // Skip comments and empty lines
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            
            // Check for build_commands section
            if (trimmed.equals("build_commands:")) {
                inBuildCommands = true;
                inIndexes = false;
                inQuery = false;
                currentScope = null;
                currentCollection = null;
                currentIndex = null;
                currentBuildCommand = null;
                continue;
            }
            
            // Skip processing if we're in build commands section but not parsing a specific build command
            if (inBuildCommands && !trimmed.startsWith("- name:") && !trimmed.startsWith("description:") && 
                !trimmed.startsWith("query:") && currentBuildCommand == null && !inQuery) {
                continue;
            }
            
            // Parse build commands
            if (inBuildCommands && trimmed.startsWith("- name:")) {
                currentBuildCommand = new FhirConfiguration.BuildCommand();
                currentBuildCommand.setName(extractValue(trimmed));
                buildCommands.add(currentBuildCommand);
                inQuery = false;
                continue;
            }
            
            if (currentBuildCommand != null && trimmed.startsWith("description:") && inBuildCommands) {
                currentBuildCommand.setDescription(extractValue(trimmed));
                continue;
            }
            
            if (currentBuildCommand != null && trimmed.startsWith("query:") && inBuildCommands) {
                inQuery = true;
                queryBuilder = new StringBuilder();
                // Handle single line query or start of multi-line
                String queryValue = extractValue(trimmed);
                if (!queryValue.equals("|")) {
                    queryBuilder.append(queryValue);
                }
                continue;
            }
            
            // Handle multi-line query content
            if (inQuery && inBuildCommands) {
                if (trimmed.startsWith("- ") || trimmed.startsWith("#")) {
                    // End of query, save it
                    currentBuildCommand.setQuery(queryBuilder.toString().trim());
                    inQuery = false;
                    // Don't continue here, let it process the next section
                } else {
                    // Add line to query
                    if (queryBuilder.length() > 0) {
                        queryBuilder.append("\n");
                    }
                    queryBuilder.append(line); // Use original line to preserve indentation
                    continue;
                }
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
            if (currentCollection != null && trimmed.startsWith("- name:") && inIndexes && !inBuildCommands) {
                currentIndex = new FhirConfiguration.IndexConfiguration();
                String indexName = extractValue(trimmed);
                currentIndex.setName(indexName);
                currentCollection.getIndexes().add(currentIndex);
                System.out.println("DEBUG: Created index: " + indexName + " for collection: " + currentCollection.getName());
                continue;
            }
            
            // Parse index properties
            if (currentIndex != null && trimmed.startsWith("type:") && inIndexes) {
                currentIndex.setType(extractValue(trimmed));
                continue;
            }
            
            // Parse index SQL
            if (currentIndex != null && trimmed.startsWith("sql:") && inIndexes && !inBuildCommands) {
                String sqlValue = extractValue(trimmed);
                currentIndex.setSql(sqlValue);
                System.out.println("DEBUG: Setting SQL for index " + currentIndex.getName() + ": " + sqlValue);
                continue;
            }
        }
        
        // Handle case where query is at the end of file
        if (inQuery && currentBuildCommand != null) {
            currentBuildCommand.setQuery(queryBuilder.toString().trim());
        }
        
        fhirSettings.setScopes(scopes);
        fhirSettings.setBuildCommands(buildCommands);
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
