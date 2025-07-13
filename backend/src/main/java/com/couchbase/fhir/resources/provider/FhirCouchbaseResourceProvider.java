package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;


public class FhirCouchbaseResourceProvider <T extends IBaseResource> implements IResourceProvider {

    private final Class<T> resourceClass;
    private final FhirResourceDaoImpl<T> dao;
    private final FhirContext fhirContext;


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext) {
        this.resourceClass = resourceClass;
        this.dao = dao;
        this.fhirContext = fhirContext;
    }

    @Read
    public T read(@IdParam IdType theId) {
        return dao.read(resourceClass.getSimpleName(), theId.getIdPart()).orElseThrow(() ->
                new ResourceNotFoundException(theId));
    }

    @Create
    public MethodOutcome create(@ResourceParam T resource) {
        if (resource.getIdElement().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }
        if (resource instanceof DomainResource) {
            ((DomainResource) resource).getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-" + resourceClass.getSimpleName().toLowerCase());
            ((DomainResource) resource).getMeta().setLastUpdated(new Date());
        }

        FhirValidator validator = fhirContext.newValidator();
        validator.setValidateAgainstStandardSchema(true);
        validator.setValidateAgainstStandardSchematron(true);

        ValidationResult result = validator.validateWithResult(resource);
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

        T created =  dao.create(resourceClass.getSimpleName() , resource).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resourceClass.getSimpleName(), created.getIdElement().getIdPart()));
        return outcome;
    }

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }

}
