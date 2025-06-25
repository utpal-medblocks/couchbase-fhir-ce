package com.couchbase.admin.fhirBucket.service;

import com.couchbase.admin.fhirBucket.model.FhirConfiguration;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Hardcoded FHIR configuration to avoid YAML parsing complexity
 * This ensures reliable and predictable configuration loading
 */
@Component
public class FhirConfigurationProvider {
    
    public FhirConfiguration getConfiguration() {
        FhirConfiguration config = new FhirConfiguration();
        FhirConfiguration.FhirSettings fhirSettings = new FhirConfiguration.FhirSettings();
        Map<String, FhirConfiguration.ScopeConfiguration> scopes = new HashMap<>();
        
        // Admin Scope
        FhirConfiguration.ScopeConfiguration adminScope = createAdminScope();
        scopes.put("admin", adminScope);
        
        // Resources Scope
        FhirConfiguration.ScopeConfiguration resourcesScope = createResourcesScope();
        scopes.put("resources", resourcesScope);
        
        fhirSettings.setScopes(scopes);
        config.setFhir(fhirSettings);
        
        return config;
    }
    
    private FhirConfiguration.ScopeConfiguration createAdminScope() {
        FhirConfiguration.ScopeConfiguration scope = new FhirConfiguration.ScopeConfiguration();
        scope.setName("Admin");
        scope.setDescription("Administrative and metadata collections for FHIR server");
        
        List<FhirConfiguration.CollectionConfiguration> collections = new ArrayList<>();
        
        // Admin collections
        collections.add(createCollection("config", "Tenant configuration, feature flags", "Admin"));
        collections.add(createCollection("users", "User management, authentication", "Admin"));
        collections.add(createCollection("favorites", "Admin UI query favorites", "Admin"));
        collections.add(createCollection("schemas", "FHIR schemas for frontend forms", "Admin"));
        collections.add(createCollection("profiles", "US Core, custom implementation guides", "Admin"));
        collections.add(createCollection("audit-logs", "Compliance audit trail", "Admin"));
        collections.add(createCollection("system-logs", "Operational logs (debug, error)", "Admin"));
        collections.add(createCollection("terminology", "External terminology server configs", "Admin"));
        collections.add(createCollection("capability-statements", "FHIR server capabilities", "Admin"));
        
        scope.setCollections(collections);
        return scope;
    }
    
    private FhirConfiguration.ScopeConfiguration createResourcesScope() {
        FhirConfiguration.ScopeConfiguration scope = new FhirConfiguration.ScopeConfiguration();
        scope.setName("Resources");
        scope.setDescription("FHIR clinical and administrative resource collections");
        
        List<FhirConfiguration.CollectionConfiguration> collections = new ArrayList<>();
        
        // Resource collections
        collections.add(createCollection("Patient", "FHIR Patient demographic and administrative information", "Resources"));
        collections.add(createCollection("Encounter", "FHIR Encounter resources for healthcare interactions", "Resources"));
        collections.add(createCollection("Observation", "FHIR Observation resources for clinical measurements and findings", "Resources"));
        collections.add(createCollection("AllergyIntolerance", "FHIR AllergyIntolerance resources for adverse reactions", "Resources"));
        collections.add(createCollection("CarePlan", "FHIR CarePlan resources for treatment and care coordination", "Resources"));
        collections.add(createCollection("CareTeam", "FHIR CareTeam resources for care team members", "Resources"));
        collections.add(createCollection("Claim", "FHIR Claim resources for insurance claims", "Resources"));
        collections.add(createCollection("Condition", "FHIR Condition resources for diagnoses and problems", "Resources"));
        collections.add(createCollection("Device", "FHIR Device resources for medical devices and equipment", "Resources"));
        collections.add(createCollection("DiagnosticReport", "FHIR DiagnosticReport resources for test results", "Resources"));
        collections.add(createCollection("DocumentReference", "FHIR DocumentReference resources for clinical documents", "Resources"));
        collections.add(createCollection("ExplanationOfBenefit", "FHIR ExplanationOfBenefit resources for claim adjudication", "Resources"));
        collections.add(createCollection("ImagingStudy", "FHIR ImagingStudy resources for medical imaging", "Resources"));
        collections.add(createCollection("Immunization", "FHIR Immunization resources for vaccination records", "Resources"));
        collections.add(createCollection("Medication", "FHIR Medication resources for drug definitions", "Resources"));
        collections.add(createCollection("MedicationAdministration", "FHIR MedicationAdministration resources for medication delivery", "Resources"));
        collections.add(createCollection("MedicationRequest", "FHIR MedicationRequest resources for prescriptions", "Resources"));
        collections.add(createCollection("Procedure", "FHIR Procedure resources for medical procedures", "Resources"));
        collections.add(createCollection("Provenance", "FHIR Provenance resources for data origin and history", "Resources"));
        collections.add(createCollection("SupplyDelivery", "FHIR SupplyDelivery resources for supply chain management", "Resources"));
        
        scope.setCollections(collections);
        return scope;
    }
    
    private FhirConfiguration.CollectionConfiguration createCollection(String name, String description, String scopeName) {
        FhirConfiguration.CollectionConfiguration collection = new FhirConfiguration.CollectionConfiguration();
        collection.setName(name);
        collection.setDescription(description);
        
        List<FhirConfiguration.IndexConfiguration> indexes = new ArrayList<>();
        
        // Create primary index
        FhirConfiguration.IndexConfiguration primaryIndex = new FhirConfiguration.IndexConfiguration();
        primaryIndex.setName("PRIMARY");
        primaryIndex.setType("primary");
        primaryIndex.setSql(String.format("CREATE PRIMARY INDEX ON `{bucket}`.`%s`.`%s`", scopeName, name));
        
        indexes.add(primaryIndex);
        collection.setIndexes(indexes);
        
        return collection;
    }
}
