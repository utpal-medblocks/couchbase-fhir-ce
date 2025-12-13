package com.couchbase.admin.users.service;

import com.couchbase.admin.users.model.User;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class KeycloakUserManagerImpl implements KeycloakUserManager {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakUserManagerImpl.class);

    private final String keycloakUrl; // e.g. http://localhost/auth
    private final String realm; // e.g. fhir
    private final String adminUser;
    private final String adminPass;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String cachedToken = null;
    private volatile long tokenExpiryEpochMs = 0L;

    public KeycloakUserManagerImpl(
            @Value("${KEYCLOAK_URL:http://localhost/auth}") String keycloakUrl,
            @Value("${KEYCLOAK_REALM:fhir}") String realm,
            @Value("${KEYCLOAK_ADMIN_USERNAME:admin}") String adminUser,
            @Value("${KEYCLOAK_ADMIN_PASSWORD:admin}") String adminPass
    ) {
        this.keycloakUrl = keycloakUrl.endsWith("/") ? keycloakUrl.substring(0, keycloakUrl.length()-1) : keycloakUrl;
        this.realm = realm;
        this.adminUser = adminUser;
        this.adminPass = adminPass;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    // Obtain admin access token using admin-cli on master realm
    private Optional<String> obtainAdminToken() {
        // Token caching: reuse token until expiry
        try {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiryEpochMs - 5000) {
                return Optional.of(cachedToken);
            }
        } catch (Exception ignored) {}

        try {
            String tokenUrl = keycloakUrl + "/realms/master/protocol/openid-connect/token";
            String form = "grant_type=password&client_id=admin-cli&username=" +
                    URLEncoder.encode(adminUser, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(adminPass, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.error("Failed to obtain admin token: {} {}", resp.statusCode(), resp.body());
                return Optional.empty();
            }
            JsonNode json = mapper.readTree(resp.body());
            String tok = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(60);
            cachedToken = tok;
            tokenExpiryEpochMs = System.currentTimeMillis() + (expiresIn * 1000L);
            return Optional.ofNullable(tok);
        } catch (Exception e) {
            logger.error("Error obtaining Keycloak admin token", e);
            return Optional.empty();
        }
    }

    private Optional<String> findKeycloakUserIdByUsername(String username, String bearer) {
        try {
            String url = String.format("%s/admin/realms/%s/users?username=%s", keycloakUrl, realm,
                    URLEncoder.encode(username, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray() && arr.size() > 0) {
                return Optional.ofNullable(arr.get(0).path("id").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Error finding Keycloak user id by username", e);
            return Optional.empty();
        }
    }

    @Override
    public User createUser(User user, String createdBy) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("username", user.getId());
            body.put("email", user.getEmail());
            body.put("enabled", true);
            body.put("firstName", user.getUsername());
            // attributes: map role/status
            ObjectNode attrs = mapper.createObjectNode();
            if (user.getRole() != null) attrs.put("role", user.getRole());
            body.set("attributes", attrs);
            // Use plaintext password provided in passwordPlain when available
            String plain = user.getPasswordPlain();
            if (plain == null || plain.isEmpty()) {
                // fall back to passwordHash only if plaintext not provided (not ideal)
                plain = user.getPasswordHash();
            }
            if (plain != null && !plain.isEmpty()) {
                ObjectNode cred = mapper.createObjectNode();
                cred.put("type", "password");
                cred.put("value", plain);
                cred.put("temporary", false);
                body.set("credentials", mapper.createArrayNode().add(cred));
            }

            String url = String.format("%s/admin/realms/%s/users", keycloakUrl, realm);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                // Location header contains created user id
                String loc = resp.headers().firstValue("Location").orElse(null);
                if (loc != null && loc.contains("/")) {
                    String kid = loc.substring(loc.lastIndexOf('/') + 1);
                    user.setId(user.getId()); // keep our id as username
                    logger.info("Created Keycloak user {}, internal id={}", user.getId(), kid);
                    // Attempt to assign realm role if provided
                    if (user.getRole() != null && !user.getRole().isEmpty()) {
                        assignRealmRoleIfExists(token, kid, user.getRole());
                    }
                    return user;
                }
                return user;
            } else {
                logger.error("Failed to create Keycloak user: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Failed to create Keycloak user");
            }
        } catch (Exception e) {
            logger.error("Error creating Keycloak user", e);
            throw new IllegalStateException(e);
        }
    }

    private Optional<JsonNode> getKeycloakUserById(String idOrUsername, String bearer) {
        try {
            // try direct id
            String urlById = String.format("%s/admin/realms/%s/users/%s", keycloakUrl, realm,
                    URLEncoder.encode(idOrUsername, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlById))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return Optional.of(mapper.readTree(resp.body()));
            }
            // fallback: search by username
            String url = String.format("%s/admin/realms/%s/users?username=%s", keycloakUrl, realm,
                    URLEncoder.encode(idOrUsername, StandardCharsets.UTF_8));
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp2 = http.send(req2, HttpResponse.BodyHandlers.ofString());
            if (resp2.statusCode() == 200) {
                JsonNode arr = mapper.readTree(resp2.body());
                if (arr.isArray() && arr.size() > 0) return Optional.of(arr.get(0));
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Error fetching Keycloak user", e);
            return Optional.empty();
        }
    }

    private void assignRealmRoleIfExists(String bearer, String kcUserId, String roleName) {
        try {
            // fetch role representation
            String roleUrl = String.format("%s/admin/realms/%s/roles/%s", keycloakUrl, realm,
                    URLEncoder.encode(roleName, StandardCharsets.UTF_8));
            HttpRequest r = HttpRequest.newBuilder()
                    .uri(URI.create(roleUrl))
                    .header("Authorization", "Bearer " + bearer)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            JsonNode roleRep = null;
            if (resp.statusCode() != 200) {
                // role not found - attempt to create it
                logger.info("Realm role '{}' not found, attempting to create it", roleName);
                String createUrl = String.format("%s/admin/realms/%s/roles", keycloakUrl, realm);
                ObjectNode roleBody = mapper.createObjectNode();
                roleBody.put("name", roleName);
                HttpRequest createReq = HttpRequest.newBuilder()
                        .uri(URI.create(createUrl))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(roleBody)))
                        .build();
                HttpResponse<String> createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
                if (createResp.statusCode() != 201 && createResp.statusCode() != 204) {
                    logger.debug("Failed to create realm role '{}': {} {}", roleName, createResp.statusCode(), createResp.body());
                    return;
                }
                // fetch role representation again
                resp = http.send(r, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    logger.debug("Role '{}' created but could not be fetched: {}", roleName, resp.statusCode());
                    return;
                }
            }
            roleRep = mapper.readTree(resp.body());
            // POST role mapping
            String mapUrl = String.format("%s/admin/realms/%s/users/%s/role-mappings/realm", keycloakUrl, realm, kcUserId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(mapUrl))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapper.createArrayNode().add(roleRep))))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.debug("Could not assign realm role to user", e);
        }
    }


    @Override
    public Optional<User> getUserById(String userId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return Optional.empty();
        String token = tokenOpt.get();
        Optional<JsonNode> nodeOpt = getKeycloakUserById(userId, token);
        if (nodeOpt.isEmpty()) return Optional.empty();
        JsonNode n = nodeOpt.get();
        User u = new User();
        u.setId(n.path("username").asText(null));
        u.setUsername(n.path("firstName").asText(null));
        u.setEmail(n.path("email").asText(null));
        u.setStatus(n.path("enabled").asBoolean(true) ? "active" : "inactive");
        return Optional.of(u);
    }

    @Override
    public Optional<User> getUserByEmail(String email) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return Optional.empty();
        String token = tokenOpt.get();
        try {
            String url = String.format("%s/admin/realms/%s/users?email=%s", keycloakUrl, realm,
                    URLEncoder.encode(email, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            JsonNode arr = mapper.readTree(resp.body());
            if (!arr.isArray() || arr.size() == 0) return Optional.empty();
            JsonNode n = arr.get(0);
            User u = new User();
            u.setId(n.path("username").asText(null));
            u.setUsername(n.path("firstName").asText(null));
            u.setEmail(n.path("email").asText(null));
            u.setStatus(n.path("enabled").asBoolean(true) ? "active" : "inactive");
            return Optional.of(u);
        } catch (Exception e) {
            logger.debug("Error fetching user by email from Keycloak", e);
            return Optional.empty();
        }
    }

    @Override
    public List<User> getAllUsers() {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return new ArrayList<>();
        String token = tokenOpt.get();
        try {
            String url = String.format("%s/admin/realms/%s/users", keycloakUrl, realm);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return new ArrayList<>();
            JsonNode arr = mapper.readTree(resp.body());
            List<User> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    User u = new User();
                    u.setId(n.path("username").asText(null));
                    u.setUsername(n.path("firstName").asText(null));
                    u.setEmail(n.path("email").asText(null));
                    u.setStatus(n.path("enabled").asBoolean(true) ? "active" : "inactive");
                    out.add(u);
                }
            }
            return out;
        } catch (Exception e) {
            logger.error("Error fetching users from Keycloak", e);
            return new ArrayList<>();
        }
    }

    @Override
    public User updateUser(String userId, User updatedUser) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        Optional<JsonNode> nodeOpt = getKeycloakUserById(userId, token);
        if (nodeOpt.isEmpty()) throw new IllegalArgumentException("User not found in Keycloak: " + userId);
        JsonNode existing = nodeOpt.get();
        String kcId = existing.path("id").asText();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("email", updatedUser.getEmail());
            body.put("firstName", updatedUser.getUsername());
            body.put("enabled", "active".equals(updatedUser.getStatus()));
            ObjectNode attrs = mapper.createObjectNode();
            if (updatedUser.getRole() != null) attrs.put("role", updatedUser.getRole());
            body.set("attributes", attrs);

            String url = String.format("%s/admin/realms/%s/users/%s", keycloakUrl, realm, kcId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 204) {
                logger.error("Failed to update Keycloak user: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Failed to update Keycloak user");
            }
            // If a plaintext password was provided, reset it
            String plain = updatedUser.getPasswordPlain();
            if ((plain == null || plain.isEmpty()) && updatedUser.getPasswordHash() != null) {
                // fallback: use passwordHash only if plaintext not provided (best-effort)
                plain = updatedUser.getPasswordHash();
            }
            if (plain != null && !plain.isEmpty()) {
                resetPassword(token, kcId, plain);
            }
            // attempt to assign role if provided
            if (updatedUser.getRole() != null && !updatedUser.getRole().isEmpty()) {
                assignRealmRoleIfExists(token, kcId, updatedUser.getRole());
            }
            return updatedUser;
        } catch (Exception e) {
            logger.error("Error updating Keycloak user", e);
            throw new IllegalStateException(e);
        }
    }

    private void resetPassword(String bearer, String kcId, String plainPassword) {
        try {
            ObjectNode cred = mapper.createObjectNode();
            cred.put("type", "password");
            cred.put("value", plainPassword);
            cred.put("temporary", false);
            String url = String.format("%s/admin/realms/%s/users/%s/reset-password", keycloakUrl, realm, kcId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(cred)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.debug("Failed to reset Keycloak password", e);
        }
    }

    @Override
    public void deactivateUser(String userId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        Optional<JsonNode> nodeOpt = getKeycloakUserById(userId, token);
        if (nodeOpt.isEmpty()) throw new IllegalArgumentException("User not found in Keycloak: " + userId);
        String kcId = nodeOpt.get().path("id").asText();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("enabled", false);
            String url = String.format("%s/admin/realms/%s/users/%s", keycloakUrl, realm, kcId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 204) {
                logger.error("Failed to deactivate Keycloak user: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Failed to deactivate Keycloak user");
            }
        } catch (Exception e) {
            logger.error("Error deactivating Keycloak user", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void deleteUser(String userId) {
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) throw new IllegalStateException("Cannot obtain Keycloak admin token");
        String token = tokenOpt.get();
        Optional<JsonNode> nodeOpt = getKeycloakUserById(userId, token);
        if (nodeOpt.isEmpty()) throw new IllegalArgumentException("User not found in Keycloak: " + userId);
        String kcId = nodeOpt.get().path("id").asText();
        try {
            String url = String.format("%s/admin/realms/%s/users/%s", keycloakUrl, realm, kcId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 204) {
                logger.error("Failed to delete Keycloak user: {} {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("Failed to delete Keycloak user");
            }
        } catch (Exception e) {
            logger.error("Error deleting Keycloak user", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updateLastLogin(String userId) {
        // Keycloak does not provide a simple last-login setter; store as attribute
        Optional<String> tokenOpt = obtainAdminToken();
        if (tokenOpt.isEmpty()) return;
        String token = tokenOpt.get();
        Optional<JsonNode> nodeOpt = getKeycloakUserById(userId, token);
        if (nodeOpt.isEmpty()) return;
        String kcId = nodeOpt.get().path("id").asText();
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode attrs = mapper.createObjectNode();
            attrs.put("lastLogin", String.valueOf(System.currentTimeMillis()));
            body.set("attributes", attrs);
            String url = String.format("%s/admin/realms/%s/users/%s", keycloakUrl, realm, kcId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.debug("Error updating lastLogin attribute in Keycloak", e);
        }
    }
}
