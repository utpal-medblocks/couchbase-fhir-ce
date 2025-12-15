package com.couchbase.common.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic SMART on FHIR discovery endpoint
 * Returns either embedded Authorization Server endpoints or Keycloak endpoints
 * depending on the `app.security.use-keycloak` property.
 */
@RestController
public class SmartConfigurationController {

    @Value("${app.security.use-keycloak:false}")
    private boolean useKeycloak;

    @Value("${KEYCLOAK_URL:}")
    private String keycloakUrl;

    @Value("${KEYCLOAK_REALM:}")
    private String keycloakRealm;

    @Value("${KEYCLOAK_JWKS_URI:}")
    private String keycloakJwksUri;

    @Value("${app.baseUrl:}")
    private String appBaseUrl;

    @GetMapping({"/.well-known/smart-configuration", "/fhir/.well-known/smart-configuration"})
    public ResponseEntity<Map<String,Object>> smartConfiguration(HttpServletRequest req) {
        Map<String,Object> body = new HashMap<>();

        String base = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl : deriveBaseUrl(req);

        if (useKeycloak) {
            if (keycloakUrl == null || keycloakUrl.isBlank() || keycloakRealm == null || keycloakRealm.isBlank()) {
                // partially configured Keycloak - fall back to JWKS if available
                if (keycloakJwksUri != null && !keycloakJwksUri.isBlank()) {
                    body.put("jwks_uri", keycloakJwksUri);
                }
                body.put("issuer", base);
            } else {
                String issuer = keycloakUrl.endsWith("/") ? keycloakUrl.substring(0, keycloakUrl.length()-1) : keycloakUrl;
                issuer = issuer + "/realms/" + keycloakRealm;
                body.put("issuer", issuer);
                body.put("authorization_endpoint", issuer + "/protocol/openid-connect/auth");
                body.put("token_endpoint", issuer + "/protocol/openid-connect/token");
                body.put("introspection_endpoint", issuer + "/protocol/openid-connect/token/introspect");
                body.put("revocation_endpoint", issuer + "/protocol/openid-connect/revoke");
                body.put("jwks_uri", (keycloakJwksUri != null && !keycloakJwksUri.isBlank()) ? keycloakJwksUri : issuer + "/protocol/openid-connect/certs");
                body.put("registration_endpoint", issuer + "/clients-registrations/openid-connect");
            }
        } else {
            // Embedded Spring Authorization Server endpoints
            body.put("issuer", base);
            body.put("authorization_endpoint", base + "/oauth2/authorize");
            body.put("token_endpoint", base + "/oauth2/token");
            body.put("jwks_uri", base + "/oauth2/jwks");
            body.put("registration_endpoint", base + "/oauth2/register");
        }

        // Common capabilities and supported values
        body.put("scopes_supported", Arrays.asList("openid", "profile", "user/*.read", "user/*.write", "system/*.read", "system/*.write"));
        body.put("response_types_supported", Arrays.asList("code", "token", "id_token"));
        body.put("token_endpoint_auth_methods_supported", Arrays.asList("client_secret_basic", "client_secret_post"));
        body.put("capabilities", Arrays.asList("launch-ehr", "client-public", "client-confidential-symmetric"));

        return ResponseEntity.ok(body);
    }

    private String deriveBaseUrl(HttpServletRequest req) {
        String scheme = req.getScheme();
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getServerName();
        int port = req.getServerPort();
        String forwardedProto = req.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) scheme = forwardedProto;
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
        return host + (defaultPort ? "" : (":" + port));
    }
}
