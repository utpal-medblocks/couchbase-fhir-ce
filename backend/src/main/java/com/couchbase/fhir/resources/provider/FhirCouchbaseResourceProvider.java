package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.util.*;
import com.couchbase.fhir.validation.ValidationUtil;
import org.apache.jena.base.Sys;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generic FHIR resource provider for HAPI FHIR that enables CRUD operations and search capabilities
 * for any FHIR resource type backed by a Couchbase data store.
 *
 * <p>This class dynamically handles requests for FHIR resources using the generic type {@code T}
 * and delegates persistence logic to the associated {@link FhirResourceDaoImpl}. It integrates
 * validation using the HAPI FHIR validation API and ensures the resource conforms to US Core profiles
 * when applicable.</p>
 *
 * @param <T> A FHIR resource type extending {@link Resource}
 */

public class FhirCouchbaseResourceProvider <T extends Resource> implements IResourceProvider {

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
    public MethodOutcome create(@ResourceParam T resource) throws IOException {
        if (resource.getIdElement().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }
        if (resource instanceof DomainResource) {
            ((DomainResource) resource).getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-" +  resource.getClass().getSimpleName().toLowerCase());
            ((DomainResource) resource).getMeta().setLastUpdated(new Date());
        }

        ValidationUtil validationUtil = new ValidationUtil();
        ValidationResult result = validationUtil.validate(resource , resourceClass.getSimpleName() , fhirContext);
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


        T created =  dao.create( resource.getClass().getSimpleName() , resource).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resourceClass.getSimpleName(), created.getIdElement().getIdPart()));
        return outcome;
    }

    @Search(allowUnknownParams = true)
    public Bundle search(RequestDetails requestDetails) {

        List<String> filters = new ArrayList<>();
        List<String> revIncludes = new ArrayList<>();
        Map<String, String[]> rawParams = requestDetails.getParameters();
        // Flatten and convert to Map<String, String>
        Map<String, String> searchParams = rawParams.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue()[0]
                ));

        String resourceType = resourceClass.getSimpleName();
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        for (Map.Entry<String, String> entry : searchParams.entrySet()) {
            String paramName = entry.getKey();
            paramName = QueryBuilder.getActualFieldName(fhirContext , resourceType, paramName);
            String value = entry.getValue();

            if(paramName.equalsIgnoreCase("_revinclude")){
                revIncludes.add(value);
            }else{
                RuntimeSearchParam searchParam = fhirContext
                        .getResourceDefinition(resourceType)
                        .getSearchParam(paramName);

                if (searchParam == null) continue;

                if (searchParam.getParamType() == RestSearchParameterTypeEnum.TOKEN) {
                    filters.add(TokenSearchHelper.buildTokenWhereClause(fhirContext, resourceType, paramName, value));
                }else if(searchParam.getParamType() == RestSearchParameterTypeEnum.STRING){
                    String searchClause = StringSearchHelper.buildStringWhereCluse(fhirContext , resourceType , paramName , value , searchParam);
                    if(searchClause != null){
                        filters.add(searchClause);
                    }
                }else if(searchParam.getParamType() == RestSearchParameterTypeEnum.DATE){
                    String dateClause = DateSearchHelper.buildDateCondition(fhirContext , resourceType , paramName , value);
                    filters.add(dateClause);
                }
            }
        }


        QueryBuilder queryBuilder = new QueryBuilder();
        List<T> results = dao.search(resourceType, queryBuilder.buildQuery(filters , revIncludes , resourceType));

        // Construct a FHIR Bundle response
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(results.size());

        for (T resource : results) {
            bundle.addEntry()
                    .setResource(resource)
                    .setFullUrl(resourceType + "/" + resource.getIdElement().getIdPart());
        }

        return bundle;
    }

    @Transaction
    public Bundle transaction(@TransactionParam Bundle bundle) {
        Bundle responseBundle = new Bundle();
        responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);

        BundleProcessor processor = new BundleProcessor();
        processor.process(bundle);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            MethodOutcome outcome = null;

            if (entry.getRequest().getMethod() == Bundle.HTTPVerb.POST) {
                outcome = createResource((T) resource);
                System.out.println( resource.getClass().getSimpleName() +" id - "+ outcome.getId().toUnqualifiedVersionless().getValue());
            } else if (entry.getRequest().getMethod() == Bundle.HTTPVerb.PUT) {
               // outcome = updateResource((T) resource);
                System.out.println("update resource :: PUT method called");
            } else {
                throw new UnprocessableEntityException("Unsupported HTTP verb: " + entry.getRequest().getMethod());
            }

            // Build response entry
            Bundle.BundleEntryComponent responseEntry = new Bundle.BundleEntryComponent();
            responseEntry.setResponse(new Bundle.BundleEntryResponseComponent()
                    .setStatus("201 Created")
                    .setLocation(outcome.getId().toUnqualifiedVersionless().getValue()));
            responseEntry.setResource((Resource)outcome.getResource());
            responseBundle.addEntry(responseEntry);
        }

        return responseBundle;
    }


    private MethodOutcome createResource(T resource) {
        try {


            if (resource instanceof DomainResource) {
            //    ((DomainResource) resource).getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-" +  resource.getClass().getSimpleName().toLowerCase());
                ((DomainResource) resource).getMeta().setLastUpdated(new Date());
            }
            ValidationUtil validationUtil = new ValidationUtil();
            ValidationResult result = validationUtil.validate(resource, resourceClass.getSimpleName(), fhirContext);
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

            T created = dao.create(resource.getClass().getSimpleName(), resource).orElseThrow(() ->
                    new InternalErrorException("Failed to create resource"));
            return new MethodOutcome(new IdType(resource.getClass().getSimpleName(), created.getIdElement().getIdPart()))
                    .setCreated(true)
                    .setResource(created);
        } catch (Exception e) {
            throw new InternalErrorException("Error creating resource: " + e.getMessage(), e);
        }
    }



    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }

}
