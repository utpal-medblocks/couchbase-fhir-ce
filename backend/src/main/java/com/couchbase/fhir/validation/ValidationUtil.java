package com.couchbase.fhir.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Resource;

/**
 * Utility class for validating FHIR resources against US Core StructureDefinitions.
 *
 * <p>This class provides a method to perform schema and schematron validation
 * on any FHIR {@link Resource}, using a corresponding custom StructureDefinition
 * loaded from the application's classpath (typically under {@code /us_core/}).</p>
 *
 * <p>The validation is performed using HAPI FHIR's {@link FhirValidator} and
 * {@link FhirInstanceValidator}, with a {@link ValidationSupportChain} combining
 * preloaded profiles and default validation support.</p>
 */

public class ValidationUtil {


    public  ValidationResult validate(Resource resource  , String resourceName, FhirContext fhirContext) {

        FhirValidator validator = fhirContext.newValidator();
        validator.setValidateAgainstStandardSchema(true);
        validator.setValidateAgainstStandardSchematron(true);
        ValidationSupportChain chain = ValidationSupportChainUtil.buildValidationSupport(fhirContext);
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(chain);
        validator.registerValidatorModule(instanceValidator);
        return validator.validateWithResult(resource);
    }


}
