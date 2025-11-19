package com.couchbase.fhir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/**
 * Dev-only helper controller to make SMART / OAuth testing easier.
 *
 * When the user is redirected back to /authorized with a ?code=...,
 * this controller will:
 *  1. Exchange the authorization code for tokens at /oauth2/token
 *  2. Render a simple HTML page showing the access token, id token, etc.
 *
 * This is similar to Medplum's "copy your token" testing UI and is NOT
 * intended for production use.
 */
@Controller
public class OAuthDebugController {

    private static final Logger logger = LoggerFactory.getLogger(OAuthDebugController.class);

    // Dev-only test client credentials (must match AuthorizationServerConfig.registeredClientRepository)
    private static final String TEST_CLIENT_ID = "test-client";
    private static final String TEST_CLIENT_SECRET = "test-secret";

    @GetMapping("/authorized")
    public String authorized(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            Model model,
            HttpServletRequest request
    ) {
        model.addAttribute("state", state);

        if (error != null) {
            model.addAttribute("error", error);
            return "authorized";
        }

        if (code == null || code.isEmpty()) {
            model.addAttribute("error", "Missing authorization code");
            return "authorized";
        }

        try {
            // Build redirect_uri to exactly match what was used in the authorize request
            String redirectUri = ServletUriComponentsBuilder.fromRequest(request)
                    .replaceQuery(null)
                    .build()
                    .toUriString();

            // Build token endpoint URL based on current host (http(s)://host:port/oauth2/token)
            String tokenEndpoint = ServletUriComponentsBuilder.fromRequest(request)
                    .replacePath("/oauth2/token")
                    .replaceQuery(null)
                    .build()
                    .toUriString();

            logger.debug("Exchanging code for token at {}", tokenEndpoint);

            RestTemplate restTemplate = new RestTemplate();

            // Prepare request body
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "authorization_code");
            body.add("code", code);
            body.add("redirect_uri", redirectUri);

            // Basic auth header for test-client
            String basicAuth = TEST_CLIENT_ID + ":" + TEST_CLIENT_SECRET;
            String encodedAuth = Base64.getEncoder().encodeToString(basicAuth.getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    tokenEndpoint,
                    HttpMethod.POST,
                    entity,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                model.addAttribute("error", "Token endpoint returned " + response.getStatusCode());
                return "authorized";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> tokens = response.getBody();
            model.addAttribute("tokens", tokens);

            // Compute human-readable expiry time if expires_in present
            Object expiresInObj = tokens.get("expires_in");
            if (expiresInObj instanceof Number) {
                long expiresIn = ((Number) expiresInObj).longValue();
                Instant expiresAt = Instant.now().plusSeconds(expiresIn);
                String expiresAtIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .withZone(ZoneId.systemDefault())
                        .format(expiresAt);
                model.addAttribute("expiresAt", expiresAtIso);
            }

            return "authorized";
        } catch (Exception ex) {
            logger.error("Failed to exchange authorization code for tokens", ex);
            model.addAttribute("error", "Failed to exchange code for token: " + ex.getMessage());
            return "authorized";
        }
    }
}


