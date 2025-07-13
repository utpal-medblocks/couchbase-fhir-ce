package com.couchbase.fhir.resources.service;

import org.springframework.stereotype.Service;

@Service
public class PatientService {

  /*  @Autowired
    private PatientDao patientDao;

    @Autowired
    private FhirContext fhirContext;

    public Optional<Patient> read(String id) {
        return  patientDao.read("Patient", id)
                .map(patient -> {
                    //  Inject US Core profile at read-time - temporary
                    if (!patient.getMeta().hasProfile(USCoreProfiles.US_CORE_PATIENT_PROFILE)) {
                        patient.getMeta().addProfile(USCoreProfiles.US_CORE_PATIENT_PROFILE);
                    }
                    return patient;
                });
    }

    public Optional<Patient> create(Patient patient) {
        if(!patient.hasId()){
            patient.setId(UUID.randomUUID().toString());
        }

        if(!patient.getMeta().hasProfile(USCoreProfiles.US_CORE_PATIENT_PROFILE)) {
            patient.getMeta().addProfile(USCoreProfiles.US_CORE_PATIENT_PROFILE);
        }
        patient.getMeta().setLastUpdated(new Date());

        // Validate before persisting
        FhirValidator validator = fhirContext.newValidator();
        validator.setValidateAgainstStandardSchema(true);
        validator.setValidateAgainstStandardSchematron(true);

        ValidationResult result = validator.validateWithResult(patient);

        if (!result.isSuccessful()) {
            StringBuilder issues = new StringBuilder();
            result.getMessages().forEach(msg -> issues.append(msg.getSeverity())
                    .append(": ")
                    .append(msg.getLocationString())
                    .append(" - ")
                    .append(msg.getMessage())
                    .append("\n"));

            throw new UnprocessableEntityException("FHIR Validation failed:\n" + issues.toString());
        }

        return patientDao.create("Patient" , patient);

    }*/
}
