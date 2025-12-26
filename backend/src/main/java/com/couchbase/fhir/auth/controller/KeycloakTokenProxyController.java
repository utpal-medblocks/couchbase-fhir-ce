package com.couchbase.fhir.auth.controller;

import com.couchbase.fhir.auth.util.TokenResponseEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Proxy controller that forwards token requests to Keycloak and enhances the
 * token response with top-level SMART claims (patient, fhirUser) when present
 * inside the access_token JWT.
 */
@RestController
public class KeycloakTokenProxyController {
    private static final Logger logger = LoggerFactory.getLogger(KeycloakTokenProxyController.class);

    private final RestTemplate rest = new RestTemplate();

    @Value("${KEYCLOAK_TOKEN_ENDPOINT:}")
    private String keycloakTokenEndpoint;

    @Value("${KEYCLOAK_URL:}")
    private String keycloakUrl;

    @Value("${KEYCLOAK_REALM:}")
    private String keycloakRealm;

    @PostMapping(value = "/auth/keycloak/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> proxyToken(HttpServletRequest req) throws IOException {
        // Read request body
        String body = StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8);

        // Determine Keycloak token endpoint
        String tokenEndpoint = keycloakTokenEndpoint;
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            // Fallback: construct from KEYCLOAK_URL and KEYCLOAK_REALM
            if (keycloakUrl == null || keycloakUrl.isBlank() || keycloakRealm == null || keycloakRealm.isBlank()) {
                logger.error("Keycloak token endpoint not configured and KEYCLOAK_URL/KEYCLOAK_REALM not set");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Keycloak token endpoint not configured");
            }
            // Ensure no trailing slash
            String base = keycloakUrl.endsWith("/") ? keycloakUrl.substring(0, keycloakUrl.length() - 1) : keycloakUrl;
            tokenEndpoint = base + "/realms/" + keycloakRealm + "/protocol/openid-connect/token";
        }

        // Prepare headers for forwarding
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String auth = req.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            headers.set("Authorization", auth);
        }

        try {
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> kcResp = rest.postForEntity(tokenEndpoint, entity, String.class);

            HttpHeaders out = new HttpHeaders();
            out.setContentType(MediaType.APPLICATION_JSON);
            out.setCacheControl(CacheControl.noStore());
            out.set("Pragma", "no-cache");

            if (!kcResp.getStatusCode().is2xxSuccessful()) {
                // Forward error response
                return ResponseEntity.status(kcResp.getStatusCode()).headers(out).body(kcResp.getBody());
            }

            String original = kcResp.getBody();
            String enhanced = TokenResponseEnhancer.enhance(original);

            return ResponseEntity.ok().headers(out).body(enhanced);
        } catch (HttpClientErrorException e) {
            // Forward Keycloak error body and status
            HttpHeaders out = new HttpHeaders();
            out.setContentType(MediaType.APPLICATION_JSON);
            out.setCacheControl(CacheControl.noStore());
            out.set("Pragma", "no-cache");
            String respBody = e.getResponseBodyAsString();
            return ResponseEntity.status(e.getStatusCode()).headers(out).body(respBody);
        } catch (Exception e) {
            logger.error("Failed to proxy token request to Keycloak: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to proxy token request");
        }
    }
}
