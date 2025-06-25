package com.couchbase.admin.fhirBucket.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.config.location=classpath:application.yml"
})
public class FhirBucketPropertiesTest {

    @Autowired
    private FhirBucketProperties fhirProperties;

    @Test
    public void testConfigurationPropertiesLoaded() {
        // Verify the configuration was loaded
        assertNotNull(fhirProperties, "FhirBucketProperties should not be null");
        assertNotNull(fhirProperties.getScopes(), "Scopes should not be null");
        assertFalse(fhirProperties.getScopes().isEmpty(), "Scopes should not be empty");
        
        // Verify admin scope
        assertTrue(fhirProperties.getScopes().containsKey("admin"), "Admin scope should exist");
        FhirBucketProperties.ScopeConfiguration adminScope = fhirProperties.getScopes().get("admin");
        assertEquals("Admin", adminScope.getName(), "Admin scope name should be 'Admin'");
        assertNotNull(adminScope.getCollections(), "Admin collections should not be null");
        assertFalse(adminScope.getCollections().isEmpty(), "Admin collections should not be empty");
        
        // Verify resources scope
        assertTrue(fhirProperties.getScopes().containsKey("resources"), "Resources scope should exist");
        FhirBucketProperties.ScopeConfiguration resourcesScope = fhirProperties.getScopes().get("resources");
        assertEquals("Resources", resourcesScope.getName(), "Resources scope name should be 'Resources'");
        assertNotNull(resourcesScope.getCollections(), "Resources collections should not be null");
        assertFalse(resourcesScope.getCollections().isEmpty(), "Resources collections should not be empty");
        
        // Verify build commands
        assertNotNull(fhirProperties.getBuildCommands(), "Build commands should not be null");
        assertFalse(fhirProperties.getBuildCommands().isEmpty(), "Build commands should not be empty");
        
        // Verify first build command
        FhirBucketProperties.BuildCommand firstBuildCommand = fhirProperties.getBuildCommands().get(0);
        assertEquals("Build Deferred Indexes", firstBuildCommand.getName(), "First build command name should match");
        assertNotNull(firstBuildCommand.getQuery(), "Build command query should not be null");
        assertFalse(firstBuildCommand.getQuery().trim().isEmpty(), "Build command query should not be empty");
        
        // Verify collections have indexes
        for (FhirBucketProperties.ScopeConfiguration scope : fhirProperties.getScopes().values()) {
            for (FhirBucketProperties.CollectionConfiguration collection : scope.getCollections()) {
                assertNotNull(collection.getIndexes(), "Collection indexes should not be null");
                assertFalse(collection.getIndexes().isEmpty(), "Collection should have at least one index");
                
                for (FhirBucketProperties.IndexConfiguration index : collection.getIndexes()) {
                    assertNotNull(index.getName(), "Index name should not be null");
                    assertNotNull(index.getSql(), "Index SQL should not be null");
                    assertFalse(index.getSql().trim().isEmpty(), "Index SQL should not be empty");
                }
            }
        }
    }
} 