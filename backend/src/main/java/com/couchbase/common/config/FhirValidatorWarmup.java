package com.couchbase.common.config;

import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Component to warm up FHIR validators after application startup
 * Uses ApplicationRunner to run after all beans are fully initialized
 */
@Component
public class FhirValidatorWarmup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FhirValidatorWarmup.class);

    @Autowired
    private FhirValidator fhirValidator; // Primary validator

    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator basicFhirValidator;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("🔥 Pre-loading FHIR validators to avoid first-request delay...");
        
        try {
            // Create a comprehensive patient that triggers ValueSet and CodeSystem loading
            Patient comprehensivePatient = new Patient()
                // Basic identifiers and names
                .addIdentifier(new Identifier()
                    .setSystem("http://hl7.org/fhir/sid/us-ssn")
                    .setValue("123-45-6789"))
                .addName(new HumanName()
                    .setFamily("WarmUp")
                    .addGiven("Test")
                    .setUse(HumanName.NameUse.OFFICIAL))
                
                // Gender enum - triggers AdministrativeGender ValueSet loading
                .setGender(AdministrativeGender.UNKNOWN)
                
                // Birth date and active status
                .setBirthDate(new java.util.Date())
                .setActive(true)
                
                // Address with US Core elements
                .addAddress(new Address()
                    .setUse(Address.AddressUse.HOME)
                    .addLine("123 Main St")
                    .setCity("Anytown")
                    .setState("CA")
                    .setPostalCode("12345")
                    .setCountry("US"))
                
                // Telecom with contact point use - triggers ContactPointUse ValueSet
                .addTelecom(new ContactPoint()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.HOME)
                    .setValue("555-1234"))
                
                // Language - triggers language ValueSet
                .addCommunication(new Patient.PatientCommunicationComponent()
                    .setLanguage(new CodeableConcept()
                        .addCoding(new Coding()
                            .setSystem("urn:ietf:bcp:47")
                            .setCode("en-US")
                            .setDisplay("English (United States)"))));
            
            // Warm up primary FHIR validator with comprehensive patient
            logger.info("🔄 Warming up primary FHIR validator with ValueSet loading...");
            ValidationResult primaryResult = fhirValidator.validateWithResult(comprehensivePatient);
            
            // Warm up basic FHIR validator
            logger.info("🔄 Warming up basic FHIR validator...");
            ValidationResult basicResult = basicFhirValidator.validateWithResult(comprehensivePatient);
            
            logger.info("✅ FHIR validators pre-loaded successfully!");
            logger.info("📊 Primary validator processed {} validation messages", primaryResult.getMessages().size());
            logger.info("📊 Basic validator processed {} validation messages", basicResult.getMessages().size());
            logger.info("🚀 First requests will now be fast - ValueSets and CodeSystems are loaded!");
            
        } catch (Exception e) {
            logger.warn("⚠️ FHIR validator warmup failed (this won't affect functionality): {}", e.getMessage());
            logger.debug("Warmup error details:", e);
        }
    }
}
