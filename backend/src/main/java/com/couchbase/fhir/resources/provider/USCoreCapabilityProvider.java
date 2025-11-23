package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.couchbase.fhir.resources.constants.USCoreProfiles;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;

import java.util.List;

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

    private static final Logger logger = LoggerFactory.getLogger(USCoreCapabilityProvider.class);
    private final String appVersion;
    private final String configuredBaseUrl; // Injected base URL (expected to include /fhir)
    private volatile CachedStatement cachedStatement; // Single cached statement (no tenants)
    private static final long CACHE_TTL_MS = 3600000L; // 1 hour

    private static class CachedStatement {
        final CapabilityStatement statement;
        final long timestamp;
        CachedStatement(CapabilityStatement statement) { this.statement = statement; this.timestamp = System.currentTimeMillis(); }
        boolean isExpired() { return System.currentTimeMillis() - timestamp > CACHE_TTL_MS; }
    }

    public USCoreCapabilityProvider(RestfulServer restfulServer,
                                    @Autowired(required = false) BuildProperties buildProperties,
                                    @org.springframework.beans.factory.annotation.Value("${app.baseUrl}") String baseUrl) {
        super(restfulServer);
        
        // Determine version: prefer build-info, then JAR manifest, else dev
        String v = null;
        if (buildProperties != null) {
            try {
                v = buildProperties.getVersion();
            } catch (Exception ignored) { }
        }
        if (v == null) {
            v = getClass().getPackage().getImplementationVersion();
        }
        this.appVersion = v != null ? v : "dev";
        // Normalize configured base URL to ensure it ends with /fhir
        this.configuredBaseUrl = baseUrl.endsWith("/fhir") ? baseUrl : (baseUrl + "/fhir");
        logger.info("✅ USCoreCapabilityProvider initialized (no tenant mode) version={} baseUrl={}", appVersion, this.configuredBaseUrl);
    }

    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {
        long startTime = System.currentTimeMillis();
        
        // Check single cache (no multi-tenant)
        CachedStatement cached = cachedStatement;
        if (cached != null && !cached.isExpired()) {
            logger.debug("📋 Using cached CapabilityStatement (cached {} ms ago)", System.currentTimeMillis() - cached.timestamp);
            return cached.statement.copy();
        }
        logger.info("📋 Generating new CapabilityStatement (cache miss or expired)...");
        
        CapabilityStatement statement = (CapabilityStatement) super.getServerConformance(request, requestDetails);

        // ============================================
        // BRANDING
        // ============================================
        statement.setName("Couchbase FHIR CE");
        statement.setPublisher("Couchbase Labs");
        statement.setStatus(Enumerations.PublicationStatus.ACTIVE);
        
        // Update text/narrative
        statement.getText()
            .setStatus(org.hl7.fhir.r4.model.Narrative.NarrativeStatus.GENERATED)
            .setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">Couchbase FHIR CE - US Core Compatible FHIR Server</div>");
        
        // Software block
        if (statement.hasSoftware()) {
            statement.getSoftware()
                .setName("Couchbase FHIR CE")
                .setVersion(appVersion);
        } else {
            statement.setSoftware(new CapabilityStatement.CapabilityStatementSoftwareComponent()
                .setName("Couchbase FHIR CE")
                .setVersion(appVersion));
        }
        
        // Implementation base URL (prefer requestDetails server base if provided, else configured)
        String fhirBase = (requestDetails != null && requestDetails.getFhirServerBase() != null && !requestDetails.getFhirServerBase().isBlank())
                ? requestDetails.getFhirServerBase()
                : this.configuredBaseUrl;
        if (fhirBase == null || fhirBase.isBlank()) {
            throw new IllegalStateException("Base FHIR server URL missing (app.baseUrl must be configured)");
        }
        if (statement.hasImplementation()) {
            statement.getImplementation().setDescription("Couchbase FHIR CE").setUrl(fhirBase);
        } else {
            statement.setImplementation(new CapabilityStatement.CapabilityStatementImplementationComponent()
                .setDescription("Couchbase FHIR CE")
                .setUrl(fhirBase));
        }

        // ============================================
        // US CORE PROFILES
        // ============================================
        // Add US Core server conformance URL
        statement.addInstantiates(USCoreProfiles.US_CORE_SERVER);

        // Add US Core profile URLs to each resource type
        // Note: We don't manually enumerate search parameters here because:
        // 1. HAPI already includes them in the base capability statement
        // 2. Manually enumerating them is very slow (10+ seconds)
        // 3. It creates duplicate entries
        List<CapabilityStatement.CapabilityStatementRestResourceComponent> resources = statement.getRestFirstRep().getResource();

        for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : resources) {
            String resourceType = resource.getType();
            String supportedProfileUrl = US_CORE_BASE_URL + resourceType.toLowerCase();

            // Add the US Core supportedProfile if not already present
            if (resource.getSupportedProfile().stream().noneMatch(profile -> profile.getValue().equals(supportedProfileUrl))) {
                resource.addSupportedProfile(supportedProfileUrl);
            }
        }

        // ============================================
        // SMART ON FHIR SECURITY
        // ============================================
        addSmartSecurityExtension(statement, fhirBase);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("✅ CapabilityStatement generated in {} ms", elapsed);
        cachedStatement = new CachedStatement(statement.copy());
        
        return statement;
    }

    /**
     * Add SMART on FHIR security extension to CapabilityStatement
     * This advertises OAuth 2.0 endpoints and supported capabilities
     * 
     * Reference: http://hl7.org/fhir/smart-app-launch/conformance.html
     */
    private void addSmartSecurityExtension(CapabilityStatement statement, String fhirBase) {
        // Get or create the REST component
        CapabilityStatement.CapabilityStatementRestComponent rest = statement.getRestFirstRep();
        
        // Get or create security component
        CapabilityStatement.CapabilityStatementRestSecurityComponent security;
        if (rest.hasSecurity()) {
            security = rest.getSecurity();
        } else {
            security = new CapabilityStatement.CapabilityStatementRestSecurityComponent();
            rest.setSecurity(security);
        }
        
        // Add OAuth2 service type
        CodeableConcept oauthService = new CodeableConcept();
        oauthService.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service")
            .setCode("SMART-on-FHIR")
            .setDisplay("SMART-on-FHIR");
        security.addService(oauthService);
        
        // Add SMART extension with OAuth endpoints
        // Extract base URL (remove /fhir if present)
        String baseUrl = fhirBase.endsWith("/fhir") 
            ? fhirBase.substring(0, fhirBase.length() - 5) 
            : fhirBase;
        
        Extension smartExtension = new Extension();
        smartExtension.setUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
        
        // Token endpoint
        Extension tokenExtension = new Extension();
        tokenExtension.setUrl("token");
        tokenExtension.setValue(new UriType(baseUrl + "/oauth2/token"));
        smartExtension.addExtension(tokenExtension);
        
        // Authorize endpoint
        Extension authorizeExtension = new Extension();
        authorizeExtension.setUrl("authorize");
        authorizeExtension.setValue(new UriType(baseUrl + "/oauth2/authorize"));
        smartExtension.addExtension(authorizeExtension);
        
        // Introspect endpoint
        Extension introspectExtension = new Extension();
        introspectExtension.setUrl("introspect");
        introspectExtension.setValue(new UriType(baseUrl + "/oauth2/introspect"));
        smartExtension.addExtension(introspectExtension);
        
        // Revoke endpoint
        Extension revokeExtension = new Extension();
        revokeExtension.setUrl("revoke");
        revokeExtension.setValue(new UriType(baseUrl + "/oauth2/revoke"));
        smartExtension.addExtension(revokeExtension);
        
        security.addExtension(smartExtension);
        
        // Add description
        security.setDescription("This server supports SMART on FHIR authorization using OAuth 2.0. " +
                               "Supported scopes include patient/*, user/*, and system/* for granular access control.");
        
        logger.debug("Added SMART on FHIR security extension to CapabilityStatement with base URL: {}", baseUrl);
    }

}
