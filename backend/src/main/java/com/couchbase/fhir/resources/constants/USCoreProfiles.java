package com.couchbase.fhir.resources.constants;

import java.util.*;

/**
 * Defines a centralized mapping of FHIR resource types to their corresponding
 * US Core profile URLs as specified in the HL7 US Core Implementation Guide.
 *
 * This class is used to declare supported profiles in the server's
 * CapabilityStatement, ensuring compliance with US Core requirements
 * and compatibility with validation and certification tools like Inferno.
 *
 * US Core 6.1.0 requires declaring specific sub-profiles for certain resource types.
 *
 * @author Utpal Sarmah
 */
public class USCoreProfiles {

    public static final String US_CORE_SERVER = "http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server";
    public static final String US_CORE_BASE_URL = "http://hl7.org/fhir/us/core/StructureDefinition/";
    
    // Resource type to profile URLs mapping
    // Some resources have multiple profiles (e.g., Observation has lab, vital signs, etc.)
    private static final Map<String, List<String>> RESOURCE_PROFILES = new HashMap<>();
    
    static {
        // Condition profiles
        RESOURCE_PROFILES.put("Condition", Arrays.asList(
            US_CORE_BASE_URL + "us-core-condition-encounter-diagnosis",
            US_CORE_BASE_URL + "us-core-condition-problems-health-concerns"
        ));
        
        // Device profiles
        RESOURCE_PROFILES.put("Device", Arrays.asList(
            US_CORE_BASE_URL + "us-core-implantable-device"
        ));
        
        // DiagnosticReport profiles
        RESOURCE_PROFILES.put("DiagnosticReport", Arrays.asList(
            US_CORE_BASE_URL + "us-core-diagnosticreport-lab",
            US_CORE_BASE_URL + "us-core-diagnosticreport-note"
        ));
        
        // Observation profiles (many sub-profiles)
        RESOURCE_PROFILES.put("Observation", Arrays.asList(
            US_CORE_BASE_URL + "us-core-observation-lab",
            US_CORE_BASE_URL + "us-core-blood-pressure",
            US_CORE_BASE_URL + "us-core-bmi",
            US_CORE_BASE_URL + "us-core-head-circumference",
            US_CORE_BASE_URL + "us-core-body-height",
            US_CORE_BASE_URL + "us-core-body-weight",
            US_CORE_BASE_URL + "us-core-body-temperature",
            US_CORE_BASE_URL + "us-core-heart-rate",
            US_CORE_BASE_URL + "pediatric-bmi-for-age",
            US_CORE_BASE_URL + "head-occipital-frontal-circumference-percentile",
            US_CORE_BASE_URL + "pediatric-weight-for-height",
            US_CORE_BASE_URL + "us-core-pulse-oximetry",
            US_CORE_BASE_URL + "us-core-respiratory-rate",
            US_CORE_BASE_URL + "us-core-smokingstatus",
            US_CORE_BASE_URL + "us-core-observation-clinical-result",
            US_CORE_BASE_URL + "us-core-observation-occupation",
            US_CORE_BASE_URL + "us-core-observation-pregnancyintent",
            US_CORE_BASE_URL + "us-core-observation-pregnancystatus",
            US_CORE_BASE_URL + "us-core-observation-screening-assessment"
        ));
        
        // Other resource types with single base profile
        RESOURCE_PROFILES.put("AllergyIntolerance", Arrays.asList(US_CORE_BASE_URL + "us-core-allergyintolerance"));
        RESOURCE_PROFILES.put("CarePlan", Arrays.asList(US_CORE_BASE_URL + "us-core-careplan"));
        RESOURCE_PROFILES.put("CareTeam", Arrays.asList(US_CORE_BASE_URL + "us-core-careteam"));
        RESOURCE_PROFILES.put("Coverage", Arrays.asList(US_CORE_BASE_URL + "us-core-coverage"));
        RESOURCE_PROFILES.put("DocumentReference", Arrays.asList(US_CORE_BASE_URL + "us-core-documentreference"));
        RESOURCE_PROFILES.put("Encounter", Arrays.asList(US_CORE_BASE_URL + "us-core-encounter"));
        RESOURCE_PROFILES.put("Goal", Arrays.asList(US_CORE_BASE_URL + "us-core-goal"));
        RESOURCE_PROFILES.put("Immunization", Arrays.asList(US_CORE_BASE_URL + "us-core-immunization"));
        RESOURCE_PROFILES.put("Location", Arrays.asList(US_CORE_BASE_URL + "us-core-location"));
        RESOURCE_PROFILES.put("Medication", Arrays.asList(US_CORE_BASE_URL + "us-core-medication"));
        RESOURCE_PROFILES.put("MedicationDispense", Arrays.asList(US_CORE_BASE_URL + "us-core-medicationdispense"));
        RESOURCE_PROFILES.put("MedicationRequest", Arrays.asList(US_CORE_BASE_URL + "us-core-medicationrequest"));
        RESOURCE_PROFILES.put("Organization", Arrays.asList(US_CORE_BASE_URL + "us-core-organization"));
        RESOURCE_PROFILES.put("Patient", Arrays.asList(US_CORE_BASE_URL + "us-core-patient"));
        RESOURCE_PROFILES.put("Practitioner", Arrays.asList(US_CORE_BASE_URL + "us-core-practitioner"));
        RESOURCE_PROFILES.put("PractitionerRole", Arrays.asList(US_CORE_BASE_URL + "us-core-practitionerrole"));
        RESOURCE_PROFILES.put("Procedure", Arrays.asList(US_CORE_BASE_URL + "us-core-procedure"));
        RESOURCE_PROFILES.put("Provenance", Arrays.asList(US_CORE_BASE_URL + "us-core-provenance"));
        RESOURCE_PROFILES.put("ServiceRequest", Arrays.asList(US_CORE_BASE_URL + "us-core-servicerequest"));
        RESOURCE_PROFILES.put("QuestionnaireResponse", Arrays.asList(US_CORE_BASE_URL + "us-core-questionnaireresponse"));
        RESOURCE_PROFILES.put("RelatedPerson", Arrays.asList(US_CORE_BASE_URL + "us-core-relatedperson"));
        RESOURCE_PROFILES.put("Specimen", Arrays.asList(US_CORE_BASE_URL + "us-core-specimen"));
    }
    
    /**
     * Get all US Core profile URLs for a given resource type.
     * 
     * @param resourceType FHIR resource type (e.g., "Patient", "Observation")
     * @return List of profile URLs for this resource type, or empty list if none defined
     */
    public static List<String> getProfilesForResourceType(String resourceType) {
        return RESOURCE_PROFILES.getOrDefault(resourceType, Collections.emptyList());
    }
    
    /**
     * Check if a resource type has US Core profiles defined.
     * 
     * @param resourceType FHIR resource type
     * @return true if profiles are defined for this resource type
     */
    public static boolean hasProfiles(String resourceType) {
        return RESOURCE_PROFILES.containsKey(resourceType);
    }
}
