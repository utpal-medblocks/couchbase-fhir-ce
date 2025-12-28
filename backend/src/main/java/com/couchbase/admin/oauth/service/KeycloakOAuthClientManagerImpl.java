package com.couchbase.admin.oauth.service;

import com.couchbase.admin.oauth.model.OAuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class KeycloakOAuthClientManagerImpl implements KeycloakOAuthClientManager {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakOAuthClientManagerImpl.class);

    private final String keycloakUrl;
    private final String realm;
    private final String adminUser;
    private final String adminPass;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String cachedToken = null;
    private volatile long tokenExpiryEpochMs = 0L;

    public KeycloakOAuthClientManagerImpl(
            @Value("${KEYCLOAK_URL:http://localhost/auth}") String keycloakUrl,
            @Value("${KEYCLOAK_REALM:fhir}") String realm,
            @Value("${KEYCLOAK_ADMIN_USERNAME:admin}") String adminUser,
            @Value("${KEYCLOAK_ADMIN_PASSWORD:admin}") String adminPass
    ) {
        this.keycloakUrl = stripQuotes(keycloakUrl).endsWith("/") ? stripQuotes(keycloakUrl).substring(0, stripQuotes(keycloakUrl).length()-1) : stripQuotes(keycloakUrl);
        this.realm = stripQuotes(realm);
        this.adminUser = stripQuotes(adminUser);
        this.adminPass = stripQuotes(adminPass);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length() >= 2) t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private Optional<String> obtainAdminToken() {
        try {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiryEpochMs - 5000) {
                return Optional.of(cachedToken);
            }
        } catch (Exception ignored) {}

        String tokenUrl = keycloakUrl + "/realms/master/protocol/openid-connect/token";
        String form = "grant_type=password&client_id=admin-cli&username=" + URLEncoder.encode(adminUser, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(adminPass, StandardCharsets.UTF_8);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Keycloak token endpoint returned {}: {}", resp.statusCode(), resp.body());
                return Optional.empty();
            }
            JsonNode json = mapper.readTree(resp.body());
            String tok = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(60);
            cachedToken = tok;
            tokenExpiryEpochMs = System.currentTimeMillis() + (expiresIn * 1000L);
            return Optional.ofNullable(tok);
        } catch (Exception e) {
            logger.debug("Error obtaining Keycloak admin token", e);
            return Optional.empty();
        }
    }

    private Optional<String> findInternalClientId(String clientId, String bearer) {
        try {
            String url = String.format("%s/admin/realms/%s/clients?clientId=%s", keycloakUrl, realm,
                    URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray() && arr.size() > 0) {
                return Optional.ofNullable(arr.get(0).path("id").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Error finding Keycloak client internal id", e);
            return Optional.empty();
        }
    }

    private OAuthClient mapFromKeycloak(JsonNode repr) {
        OAuthClient c = new OAuthClient();
        c.setClientId(repr.path("clientId").asText(null));
        c.setClientName(repr.path("name").asText(null));
        c.setPublisherUrl(repr.path("attributes").path("publisherUrl").asText(null));
        c.setClientType(repr.path("attributes").path("clientType").asText(null));
        c.setAuthenticationType(repr.path("publicClient").asBoolean(false) ? "public" : "confidential");
        c.setLaunchType(repr.path("attributes").path("launchType").asText(null));
        // redirectUris
        if (repr.has("redirectUris") && repr.path("redirectUris").isArray()) {
            List<String> r = new ArrayList<>();
            for (JsonNode u : repr.path("redirectUris")) r.add(u.asText());
            c.setRedirectUris(r);
        }
        // scopes stored as attribute 'scopes' as comma separated
        if (repr.path("attributes").has("scopes")) {
            String scopes = repr.path("attributes").path("scopes").asText(null);
            if (scopes != null && !scopes.isEmpty()) {
                String[] arr = scopes.split(",");
                List<String> s = new ArrayList<>();
                for (String ss : arr) s.add(ss.trim());
                c.setScopes(s);
            }
        }
        c.setPkceEnabled(Boolean.parseBoolean(repr.path("attributes").path("pkceEnabled").asText("true")));
        c.setPkceMethod(repr.path("attributes").path("pkceMethod").asText("S256"));
        c.setStatus(repr.path("enabled").asBoolean(true) ? "active" : "revoked");
        c.setCreatedBy(repr.path("attributes").path("createdBy").asText(null));
        if (repr.path("attributes").has("createdAt")) {
            try { c.setCreatedAt(Instant.parse(repr.path("attributes").path("createdAt").asText())); } catch (Exception ignored){}
        }
        if (repr.path("attributes").has("lastUsed")) {
            try { c.setLastUsed(Instant.parse(repr.path("attributes").path("lastUsed").asText())); } catch (Exception ignored){}
        }
        // bulkGroupId attribute
        if (repr.path("attributes").has("bulkGroupId")) {
            c.setBulkGroupId(repr.path("attributes").path("bulkGroupId").asText(null));
        }
        return c;
    }

    private boolean isSystemClient(JsonNode repr) {
        if (repr == null || repr.isMissingNode()) return false;
        String name = repr.path("name").asText("");
        if (name != null && name.startsWith("${") && name.endsWith("}")) return true;
        String cid = repr.path("clientId").asText("");
        if ("fhir-server".equals(cid)) return true;
        return false;
    }

    @Override
    public OAuthClient createClient(OAuthClient client, String plainSecret, String createdBy) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();

        try {
            ObjectNode body = mapper.createObjectNode();
            // clientId is the public identifier
            String cid = client.getClientId() != null ? client.getClientId() : "app-" + java.util.UUID.randomUUID();
            body.put("clientId", cid);
            if (client.getClientName() != null) body.put("name", client.getClientName());
            body.put("enabled", true);
            boolean pub = "public".equals(client.getAuthenticationType()) || client.getClientSecret() == null;
            body.put("publicClient", pub);
            body.put("protocol", "openid-connect");
            if (client.getRedirectUris() != null) {
                ArrayList<String> r = new ArrayList<>(client.getRedirectUris());
                body.set("redirectUris", mapper.valueToTree(r));
            }
            // attributes
            ObjectNode attrs = mapper.createObjectNode();
            if (client.getPublisherUrl() != null) attrs.put("publisherUrl", client.getPublisherUrl());
            if (client.getClientType() != null) attrs.put("clientType", client.getClientType());
            if (client.getLaunchType() != null) attrs.put("launchType", client.getLaunchType());
            if (client.getScopes() != null && !client.getScopes().isEmpty()) attrs.put("scopes", String.join(",", client.getScopes()));
            attrs.put("pkceEnabled", String.valueOf(client.isPkceEnabled()));
            attrs.put("pkceMethod", client.getPkceMethod() == null ? "S256" : client.getPkceMethod());
            attrs.put("createdBy", createdBy);
            attrs.put("createdAt", Instant.now().toString());
            // bulkGroupId may be supplied on creation
            if (client.getBulkGroupId() != null) attrs.put("bulkGroupId", client.getBulkGroupId());
            body.set("attributes", attrs);

            String url = String.format("%s/admin/realms/%s/clients", keycloakUrl, realm);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 201 && resp.statusCode() != 204) {
                logger.error("Failed to create Keycloak client: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Failed to create Keycloak client");
            }

            // Find internal id
            Optional<String> internalIdOpt = findInternalClientId(cid, token);
            if (internalIdOpt.isEmpty()) throw new IllegalStateException("Created client but could not locate internal id");
            String internalId = internalIdOpt.get();

            // If confidential and plainSecret provided, set secret via client representation
            if (!pub && plainSecret != null && !plainSecret.isEmpty()) {
                // fetch representation
                String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
                HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonNode rep = mapper.readTree(getResp.body());
                    ((ObjectNode)rep).put("secret", plainSecret);
                    HttpRequest putReq = HttpRequest.newBuilder()
                            .uri(URI.create(reprUrl))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type","application/json")
                            .timeout(Duration.ofSeconds(10))
                            .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep)))
                            .build();
                    http.send(putReq, HttpResponse.BodyHandlers.discarding());
                }
            }

            // Return mapped client
            Optional<OAuthClient> created = getClientById(cid);
            return created.orElseGet(() -> { client.setClientId(cid); client.setCreatedBy(createdBy); client.setCreatedAt(Instant.now()); return client; });

        } catch (Exception e) {
            logger.error("Error creating Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<OAuthClient> getAllClients() {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return new ArrayList<>();
        String token = tokenOpt.get();
        try {
            String url = String.format("%s/admin/realms/%s/clients", keycloakUrl, realm);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return new ArrayList<>();
            JsonNode arr = mapper.readTree(resp.body());
            List<OAuthClient> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (isSystemClient(n)) continue;
                    out.add(mapFromKeycloak(n));
                }
            }
            return out;
        } catch (Exception e) {
            logger.debug("Error fetching Keycloak clients", e);
            return new ArrayList<>();
        }
    }

    @Override
    public Optional<OAuthClient> getClientById(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return Optional.empty();
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return Optional.empty();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalIdOpt.get());
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            JsonNode rep = mapper.readTree(resp.body());
            if (isSystemClient(rep)) return Optional.empty();
            return Optional.of(mapFromKeycloak(rep));
        } catch (Exception e) {
            logger.debug("Error fetching Keycloak client by id", e);
            return Optional.empty();
        }
    }

    @Override
    public OAuthClient updateClient(String clientId, OAuthClient updates) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) throw new IllegalArgumentException("Client not found: " + clientId);
            String internalId = internalIdOpt.get();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) throw new IllegalArgumentException("Client not found: " + clientId);
            ObjectNode rep = (ObjectNode) mapper.readTree(getResp.body());

            if (isSystemClient(rep)) throw new IllegalArgumentException("Client not found: " + clientId);

            if (updates.getClientName() != null) rep.put("name", updates.getClientName());
            if (updates.getRedirectUris() != null) rep.set("redirectUris", mapper.valueToTree(updates.getRedirectUris()));
            // attributes
            ObjectNode attrs = rep.with("attributes");
            if (updates.getPublisherUrl() != null) attrs.put("publisherUrl", updates.getPublisherUrl());
            if (updates.getScopes() != null) attrs.put("scopes", String.join(",", updates.getScopes()));
            if (updates.getBulkGroupId() != null) {
                // enforce clientType when setting bulkGroupId
                String clientType = rep.path("attributes").path("clientType").asText("");
                String ct = clientType == null ? "" : clientType.toLowerCase();
                if (!(ct.contains("provider") || ct.contains("system") || ct.contains("backend"))) {
                    throw new IllegalArgumentException("Bulk group can only be attached to provider or system/backend clients");
                }
                attrs.put("bulkGroupId", updates.getBulkGroupId());
            }
            if (updates.getStatus() != null) rep.put("enabled", "active".equals(updates.getStatus()));

            HttpRequest putReq = HttpRequest.newBuilder()
                    .uri(URI.create(reprUrl))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type","application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep)))
                    .build();
            http.send(putReq, HttpResponse.BodyHandlers.discarding());

            return getClientById(clientId).orElseThrow(() -> new IllegalStateException("Failed to read updated client"));

        } catch (Exception e) {
            logger.error("Error updating Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void revokeClient(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) throw new IllegalArgumentException("Client not found: " + clientId);
            String internalId = internalIdOpt.get();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) throw new IllegalArgumentException("Client not found: " + clientId);
            ObjectNode rep = (ObjectNode) mapper.readTree(getResp.body());
            if (isSystemClient(rep)) throw new IllegalArgumentException("Client not found: " + clientId);
            rep.put("enabled", false);
            HttpRequest putReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep))).timeout(Duration.ofSeconds(10)).build();
            http.send(putReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.error("Error revoking Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void attachBulkGroup(String clientId, String bulkGroupId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) throw new IllegalArgumentException("Client not found: " + clientId);
            String internalId = internalIdOpt.get();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) throw new IllegalArgumentException("Client not found: " + clientId);
            ObjectNode rep = (ObjectNode) mapper.readTree(getResp.body());
            if (isSystemClient(rep)) throw new IllegalArgumentException("Client not found: " + clientId);
            // enforce clientType on Keycloak client
            String clientType = rep.path("attributes").path("clientType").asText("");
            String ct = clientType == null ? "" : clientType.toLowerCase();
            if (!(ct.contains("provider") || ct.contains("system") || ct.contains("backend"))) {
                throw new IllegalArgumentException("Bulk group can only be attached to provider or system/backend clients");
            }
            ObjectNode attrs = rep.with("attributes");
            attrs.put("bulkGroupId", bulkGroupId);
            HttpRequest putReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).header("Content-Type","application/json").timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep))).build();
            http.send(putReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.error("Error attaching bulk group to Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<String> getBulkGroup(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return Optional.empty();
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return Optional.empty();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalIdOpt.get());
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            JsonNode rep = mapper.readTree(resp.body());
            if (isSystemClient(rep)) return Optional.empty();
            String val = rep.path("attributes").path("bulkGroupId").asText(null);
            return Optional.ofNullable(val);
        } catch (Exception e) {
            logger.debug("Error reading bulkGroupId from Keycloak client", e);
            return Optional.empty();
        }
    }

    @Override
    public void detachBulkGroup(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return;
            String internalId = internalIdOpt.get();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) return;
            ObjectNode rep = (ObjectNode) mapper.readTree(getResp.body());
            if (isSystemClient(rep)) return;
            ObjectNode attrs = rep.with("attributes");
            attrs.remove("bulkGroupId");
            HttpRequest putReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).header("Content-Type","application/json").timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep))).build();
            http.send(putReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.error("Error detaching bulk group from Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void deleteClient(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return;
            String internalId = internalIdOpt.get();
            String url = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            // Fetch representation to ensure it's not a system client before deleting
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) return;
            JsonNode rep = mapper.readTree(getResp.body());
            if (isSystemClient(rep)) throw new IllegalArgumentException("Client not found: " + clientId);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(10)).DELETE().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.error("Error deleting Keycloak client", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verifyClientSecret(String clientId, String plainSecret) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return false;
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return false;
            String internalId = internalIdOpt.get();
            String url = String.format("%s/admin/realms/%s/clients/%s/client-secret", keycloakUrl, realm, internalId);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonNode j = mapper.readTree(resp.body());
            String val = j.path("value").asText(null);
            if (val == null) val = j.path("secret").asText(null);
            if (val == null) return false;
            return val.equals(plainSecret);
        } catch (Exception e) {
            logger.debug("Error verifying client secret with Keycloak", e);
            return false;
        }
    }

    @Override
    public void updateLastUsed(String clientId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return;
        String token = tokenOpt.get();
        try {
            Optional<String> internalIdOpt = findInternalClientId(clientId, token);
            if (internalIdOpt.isEmpty()) return;
            String internalId = internalIdOpt.get();
            String reprUrl = String.format("%s/admin/realms/%s/clients/%s", keycloakUrl, realm, internalId);
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) return;
            ObjectNode rep = (ObjectNode) mapper.readTree(getResp.body());
            ObjectNode attrs = rep.with("attributes");
            attrs.put("lastUsed", Instant.now().toString());
            HttpRequest putReq = HttpRequest.newBuilder().uri(URI.create(reprUrl)).header("Authorization","Bearer "+token).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rep))).timeout(Duration.ofSeconds(10)).build();
            http.send(putReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.debug("Failed to update last used timestamp for Keycloak client {}: {}", clientId, e.getMessage());
        }
    }
}
