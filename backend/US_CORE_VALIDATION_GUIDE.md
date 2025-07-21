# US Core 6.1 Validation Setup Guide

## Overview

This configuration adds US Core 6.1 validation support to the HAPI FHIR setup. The validation system is configured to support both basic FHIR R4 validation and comprehensive US Core profile validation.

## Configuration Details

### Beans Available

1. **`fhirValidator`** - Basic FHIR R4 validator (lightweight)
2. **`usCoreValidator`** - Full US Core 6.1 validator with profile support
3. **`validationSupportChain`** - Validation support chain with US Core capabilities
4. **`fhirContext`** - FHIR R4 context optimized for US Core
5. **`jsonParser`** - JSON parser configured for US Core resources

### Dependencies Added

The following dependencies have been added to support US Core validation:

```xml
<!-- HAPI FHIR validation dependencies for US Core 6.1 support -->
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-validation</artifactId>
    <version>${hapi.fhir.version}</version>
</dependency>
<!-- Required for NPM package support and US Core validation -->
<dependency>
    <groupId>org.hl7.fhir</groupId>
    <artifactId>org.hl7.fhir.validation</artifactId>
    <version>6.3.8</version>
</dependency>
<dependency>
    <groupId>org.hl7.fhir</groupId>
    <artifactId>org.hl7.fhir.utilities</artifactId>
    <version>6.3.8</version>
</dependency>
```

## Usage Examples

### Using the US Core Validator

```java
@Autowired
@Qualifier("usCoreValidator")
private FhirValidator usCoreValidator;

public ValidationResult validatePatient(Patient patient) {
    // Validate against US Core Patient profile
    ValidationOptions options = new ValidationOptions()
        .addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient");

    return usCoreValidator.validateWithResult(patient, options);
}
```

### Using the Basic Validator

```java
@Autowired
private FhirValidator fhirValidator;

public ValidationResult validateResource(IBaseResource resource) {
    // Basic FHIR R4 validation
    return fhirValidator.validateWithResult(resource);
}
```

## Adding US Core Package Support

To enable full US Core 6.1 validation, you need to add the US Core package to your classpath:

### Option 1: Download and Include Package

1. Download the US Core 6.1 package from: https://hl7.org/fhir/us/core/
2. Place the package file in `src/main/resources/`
3. Update the configuration to load from the correct path

### Option 2: Use NPM Package Manager

1. Install the package using npm:

   ```bash
   npm install hl7.fhir.us.core@6.1.0
   ```

2. Copy the package to your resources directory

### Option 3: Runtime Package Loading

The validator will attempt to load the package at runtime and will gracefully fall back to basic validation if the package is not available.

## Validation Features

### Supported Validations

- ✅ FHIR R4 base validation
- ✅ US Core 6.1 profile validation
- ✅ Terminology validation
- ✅ Extension validation
- ✅ Profile constraints
- ✅ Code system validation

### US Core Profiles Supported

When the US Core package is loaded, the validator supports all US Core 6.1 profiles including:

- US Core Patient
- US Core Practitioner
- US Core Organization
- US Core Observation (various types)
- US Core Medication profiles
- US Core Encounter
- US Core AllergyIntolerance
- And many more...

## Rollback Instructions

If you need to rollback these changes:

1. Remove the added dependencies from `pom.xml`
2. Revert `FhirConfig.java` to use only the basic validator
3. Remove the US Core validation beans

## Troubleshooting

### Common Issues

1. **Package not found error**: The US Core package is not available in the classpath

   - Solution: Follow the package installation instructions above

2. **Memory issues**: US Core validation can be memory intensive

   - Solution: Increase JVM memory settings

3. **Performance issues**: Full validation takes longer than basic validation
   - Solution: Use the basic validator for simple validation scenarios

### Logging

The configuration includes detailed logging to help debug validation setup:

- Look for log messages starting with 🇺🇸, 🎯, 📦, ✅, ⚠️, or ❌
- Check application logs on startup to see validation configuration status

## Version Compatibility

- HAPI FHIR: 8.2.0
- FHIR Version: R4
- US Core Version: 6.1.0
- Java: 17+
- Spring Boot: 3.5.0
