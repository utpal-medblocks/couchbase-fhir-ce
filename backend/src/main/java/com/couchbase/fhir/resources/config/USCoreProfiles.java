package com.couchbase.fhir.resources.config;

import java.util.Map;

/**
 * Defines a centralized mapping of FHIR resource types to their corresponding
 * US Core profile URLs as specified in the HL7 US Core Implementation Guide.

 * This class is used to declare supported profiles in the server's
 * CapabilityStatement, ensuring compliance with US Core requirements
 * and compatibility with validation and certification tools like Inferno.
 *
 * @author Utpal Sarmah
 */
public class USCoreProfiles {
    public static final Map<String, String> SUPPORTED_PROFILES = Map.ofEntries(
            Map.entry("Patient", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"),
            Map.entry("Observation", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation"),
            Map.entry("Condition", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition"),
            Map.entry("Encounter", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-encounter"),
            Map.entry("Organization", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-organization"),
            Map.entry("Practitioner", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitioner"),
            Map.entry("AllergyIntolerance", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance"),
            Map.entry("MedicationRequest", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-medicationrequest"),
            Map.entry("Procedure", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-procedure"),
            Map.entry("Immunization", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-immunization")
    );

    public static final String US_CORE_SERVER = "http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server";
    public static final String US_CORE_PATIENT_PROFILE = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient";

}
