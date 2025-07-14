package com.couchbase.fhir.resources.server;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.couchbase.fhir.resources.config.USCoreProfiles;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.CapabilityStatement;
import java.util.List;

import static com.couchbase.fhir.resources.config.USCoreProfiles.US_CORE_BASE_URL;


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

        for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : resources) {
            String resourceType = resource.getType();
            String supportedProfileUrl = US_CORE_BASE_URL + resourceType.toLowerCase();

            // Add the supportedProfile if not already present
            if (resource.getSupportedProfile().stream().noneMatch(profile -> profile.getValue().equals(supportedProfileUrl))) {
                resource.addSupportedProfile(supportedProfileUrl);
            }
        }

        return statement;
    }

}
