package com.couchbase.fhir.resources.constants;

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

    public static final String US_CORE_SERVER = "http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server";
    public static final String US_CORE_BASE_URL= "http://hl7.org/fhir/us/core/StructureDefinition/us-core-";
}
