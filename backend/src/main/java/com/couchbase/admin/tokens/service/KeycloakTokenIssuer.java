package com.couchbase.admin.tokens.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.Base64;

/**
 * Helper that requests Keycloak to issue a token via Token Exchange using a service client.
 */
@Component
public class KeycloakTokenIssuer {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakTokenIssuer.class);

    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public KeycloakTokenIssuer(
            @Value("${KEYCLOAK_URL:http://localhost/auth}") String keycloakUrl,
            @Value("${KEYCLOAK_REALM:fhir}") String realm,
            @Value("${KEYCLOAK_CLIENT_ID:fhir-token-issuer}") String clientId,
            @Value("${KEYCLOAK_CLIENT_SECRET:}") String clientSecret
    ) {
        this.keycloakUrl = stripQuotes(keycloakUrl).endsWith("/") ? stripQuotes(keycloakUrl).substring(0, stripQuotes(keycloakUrl).length()-1) : stripQuotes(keycloakUrl);
        this.realm = stripQuotes(realm);
        this.clientId = stripQuotes(clientId);
        this.clientSecret = stripQuotes(clientSecret);
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length() >= 2) t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * Issue a Keycloak-signed token using Token Exchange.
     * @param subjectToken the existing user token (must be a valid access token for the subject)
     * @param subject the subject (user id / username)
     * @param scopes requested scopes (FHIR scopes)
     * @param jti unique jwt id generated locally
     * @param appName application name
     * @param issuedAt issued at instant
     * @param expiresAt expiration instant
     * @return Keycloak-signed JWT access token string
     */
    public String issueToken(String subjectToken, String subject, String[] scopes, String jti, String appName, Instant issuedAt, Instant expiresAt) {
        try {
            String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl, realm);

            // Build form parameters for token-exchange
            StringJoiner sj = new StringJoiner("&");
            sj.add("grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange", StandardCharsets.UTF_8));
            if (subjectToken != null && !subjectToken.isEmpty()) {
                sj.add("subject_token=" + URLEncoder.encode(subjectToken, StandardCharsets.UTF_8));
                sj.add("subject_token_type=" + URLEncoder.encode("urn:ietf:params:oauth:token-type:access_token", StandardCharsets.UTF_8));
            }
            // requested scopes
            if (scopes != null && scopes.length > 0) {
                sj.add("scope=" + URLEncoder.encode(String.join(" ", scopes), StandardCharsets.UTF_8));
            }
            // Request an access_token explicitly
            sj.add("requested_token_type=" + URLEncoder.encode("urn:ietf:params:oauth:token-type:access_token", StandardCharsets.UTF_8));

            // client credentials: prefer HTTP Basic auth for confidential clients (when clientSecret is provided).
            boolean useBasicAuth = clientSecret != null && !clientSecret.isEmpty();
            if (!useBasicAuth) {
                if (clientId != null && !clientId.isEmpty()) sj.add("client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            }

            String form = sj.toString();

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form));

                if (useBasicAuth) {
                String cred = clientId + ":" + clientSecret;
                String basic = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + basic);
                }

                HttpRequest req = reqBuilder.build();

                logger.info("Requesting token-exchange from Keycloak for subject={} scopes={} endpoint={} basicAuth={}", subject, Arrays.toString(scopes), tokenUrl, useBasicAuth);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.error("Keycloak token-exchange failed: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Keycloak token-exchange failed: " + resp.statusCode());
            }
            JsonNode json = mapper.readTree(resp.body());
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null) {
                logger.error("Keycloak token-exchange response missing access_token: {}", resp.body());
                throw new IllegalStateException("Keycloak token-exchange did not return access_token");
            }

            logger.info("✅ Received token-exchange token from Keycloak (length={})", accessToken.length());
            return accessToken;
        } catch (Exception e) {
            logger.error("❌ Error requesting token from Keycloak", e);
            throw new RuntimeException("Failed to obtain token from Keycloak", e);
        }
    }
}
