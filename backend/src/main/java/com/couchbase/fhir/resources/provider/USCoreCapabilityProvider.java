package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.couchbase.fhir.resources.constants.USCoreProfiles;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

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
    
    // Simple cache: tenant -> capability statement
    // Cache for 1 hour (3600000 ms)
    private final Map<String, CachedStatement> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3600000L; // 1 hour
    
    private static class CachedStatement {
        final CapabilityStatement statement;
        final long timestamp;
        
        CachedStatement(CapabilityStatement statement) {
            this.statement = statement;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public USCoreCapabilityProvider(RestfulServer restfulServer, 
                                    @Autowired(required = false) BuildProperties buildProperties) {
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
        
        logger.info("✅ USCoreCapabilityProvider initialized with explicit caching (1 hour TTL), version: {}", appVersion);
    }

    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {
        long startTime = System.currentTimeMillis();
        
        // Get tenant/bucket identifier for cache key
        String tenant = "default";
        if (requestDetails != null && requestDetails.getTenantId() != null) {
            tenant = requestDetails.getTenantId();
        }
        
        // Check cache first
        CachedStatement cached = cache.get(tenant);
        if (cached != null && !cached.isExpired()) {
            logger.debug("📋 Using cached CapabilityStatement for tenant: {} (cached {} ms ago)", 
                        tenant, System.currentTimeMillis() - cached.timestamp);
            return cached.statement.copy(); // Return a copy to avoid mutations
        }
        
        logger.info("📋 Generating new CapabilityStatement for tenant: {}...", tenant);
        
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
        
        // Implementation block (per tenant/bucket)
        String fhirBase = requestDetails != null ? requestDetails.getFhirServerBase() : "http://localhost:8080/fhir";
        if (statement.hasImplementation()) {
            statement.getImplementation()
                .setDescription("Couchbase FHIR CE (" + tenant + ")")
                .setUrl(fhirBase);
        } else {
            statement.setImplementation(new CapabilityStatement.CapabilityStatementImplementationComponent()
                .setDescription("Couchbase FHIR CE (" + tenant + ")")
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

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("✅ CapabilityStatement generated in {} ms for tenant: {}", elapsed, tenant);
        
        // Cache the statement
        cache.put(tenant, new CachedStatement(statement.copy()));
        
        return statement;
    }

}
