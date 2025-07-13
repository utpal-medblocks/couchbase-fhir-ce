package com.couchbase.fhir.resources.server;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.couchbase.fhir.resources.config.USCoreProfiles;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.CapabilityStatement;
import java.util.List;


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
 * @author Utpal Sarmah
 */
public class USCoreCapabilityProvider extends ServerCapabilityStatementProvider {


    public USCoreCapabilityProvider(RestfulServer restfulServer) {
        super(restfulServer);
    }

    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {
        CapabilityStatement statement = (CapabilityStatement) super.getServerConformance(request, requestDetails);

        statement.addInstantiates(USCoreProfiles.US_CORE_SERVER);

        List<CapabilityStatement.CapabilityStatementRestResourceComponent> resources = statement.getRestFirstRep().getResource();

        // Remove any existing resources we're going to override
        resources.removeIf(r -> USCoreProfiles.SUPPORTED_PROFILES.containsKey(r.getType()));

        // Add  US Core resource declarations from constant
        USCoreProfiles.SUPPORTED_PROFILES.forEach((resourceType, profileUrl) -> {
            CapabilityStatement.CapabilityStatementRestResourceComponent resource = new CapabilityStatement.CapabilityStatementRestResourceComponent();
            resource.setType(resourceType);
            resource.setProfile(profileUrl);
            resource.addSupportedProfile(profileUrl);

            // Add standard interactions
            resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
            resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
            resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.CREATE);

            resources.add(resource);
        });

        return statement;
    }

}
