package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.couchbase.fhir.resources.constants.USCoreProfiles;
import com.google.common.base.Stopwatch;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.couchbase.fhir.resources.constants.USCoreProfiles.US_CORE_BASE_URL;


/**
 * Custom CapabilityStatementProvider that overrides the default HAPI FHIR CapabilityStatement
 * to advertise support for US Core FHIR profiles.

 * This provider dynamically registers supported resource types and their associated
 * US Core profile URLs in the CapabilityStatement (/metadata endpoint), which is
 * required for compliance with the HL7 US Core Implementation Guide and tools like Inferno.
 * Usage:
 * Register this bean in your Spring Boot or HAPI FHIR server configuration
 * to override the default CapabilityStatement generation logic.
 *
 */
public class USCoreCapabilityProvider extends ServerCapabilityStatementProvider {


    private final FhirContext fhirContext;

    public USCoreCapabilityProvider(RestfulServer restfulServer) {
        super(restfulServer);
        this.fhirContext = restfulServer.getFhirContext();
    }

    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {

        CapabilityStatement statement =  new CapabilityStatement();
        statement.setSoftware(new CapabilityStatement.CapabilityStatementSoftwareComponent()
                .setName("couchbase-fhir-ce")
                .setVersion("1.0.0"));
        statement.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        statement.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        statement.setStatus(Enumerations.PublicationStatus.ACTIVE);
        statement.addInstantiates(USCoreProfiles.US_CORE_SERVER);
        CapabilityStatement.CapabilityStatementRestComponent rest = new CapabilityStatement.CapabilityStatementRestComponent();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        Set<String> resourceTypes = fhirContext.getResourceTypes();

        for (String resourceType : resourceTypes) {
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);

            CapabilityStatement.CapabilityStatementRestResourceComponent resource =
                    new CapabilityStatement.CapabilityStatementRestResourceComponent();
            resource.setType(resourceType);

            // Add supported US Core profile URL
            String supportedProfileUrl = US_CORE_BASE_URL + resourceType.toLowerCase();
            resource.addSupportedProfile(supportedProfileUrl);

            resource.addInteraction()
                    .setCode(CapabilityStatement.TypeRestfulInteraction.READ);
            resource.addInteraction()
                    .setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);

            // Add search parameters
            for (RuntimeSearchParam param : resourceDef.getSearchParams()) {
                CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent searchParam =
                        new CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent();
                searchParam.setName(param.getName());
                searchParam.setType(Enumerations.SearchParamType.fromCode(param.getParamType().getCode()));
                searchParam.setDocumentation("FHIRPath: " + param.getPath());
                if (param.getUri() != null) {
                    searchParam.setDefinition(param.getUri());
                }
                resource.addSearchParam(searchParam);
            }

            rest.addResource(resource);
        }
        statement.addRest(rest);
        return statement;
    }

}
