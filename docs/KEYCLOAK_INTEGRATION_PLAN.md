# Proposed Technical Design Plan: Optional Keycloak Integration for SMART on FHIR

This document outlines the proposed technical design plan for optionally enabling Keycloak integration into the Couchbase FHIR Server project. The approach is intended to provide robust, enterprise-grade identity and client management for SMART on FHIR, while preserving the flexibility and simplicity of the current architecture.

---

## 1. Configuration Design

The `config.yaml` file will be extended to include a `keycloak` section, allowing administrators to enable Keycloak and provide all necessary connection and credential details:

```yaml
keycloak:
  enabled: true
  url: "http://localhost/auth"  # Route through HAProxy for resilience
  realm: "fhir"
  adminUsername: "${KEYCLOAK_ADMIN_USERNAME}"
  adminPassword: "${KEYCLOAK_ADMIN_PASSWORD}"
  clientId: "fhir-server"
  clientSecret: "${KEYCLOAK_CLIENT_SECRET}"
```

All sensitive values are referenced as environment variables or securely loaded from `config.yaml`. The Keycloak URL is routed through HAProxy, ensuring high availability and consistent routing for all services.

---

## 2. Enhanced `enable-keycloak.sh` Script

The `enable-keycloak.sh` script will be responsible for:

- **Docker Compose Modification:**
  - Adding the Keycloak service to `docker-compose.yaml` if not present.
  - Configuring the Keycloak container to read admin credentials and realm from environment variables, which are loaded from `config.yaml` at runtime (never hardcoded).
  - Ensuring Keycloak is accessible via HAProxy, updating the HAProxy config as needed.
- **Spring Application Configuration:**
  - Modifying `backend/src/main/resources/application.yaml` to set the Spring Security resource server JWT configuration to use the Keycloak JWKS URI, based on the values in `config.yaml`.
  - Disabling the internal Spring Authorization Server if Keycloak is enabled.
- **Environment Variable Management:**
  - Exporting all required Keycloak admin and client credentials as environment variables for Docker Compose and Spring Boot.
- **Initial Keycloak Provisioning:**
  - Optionally seeding Keycloak with the default realm, clients, and users using the Keycloak Admin REST API, authenticating with credentials from `config.yaml`.

---

## 3. Keycloak Routing via HAProxy

All Keycloak traffic (admin, token, auth, JWKS, registration) will be routed through HAProxy, which is already part of the deployment stack. This ensures:
- Consistent endpoint URLs for all services
- Load balancing and failover if multiple Keycloak instances are deployed
- Simplified firewall and TLS termination management

Example Keycloak endpoint in SMART discovery:
- `http://localhost/auth/realms/fhir/protocol/openid-connect/auth`

---

## 4. Spring Security & Resource Server Changes

When Keycloak is enabled, the FHIR server will:
- Configure Spring Security to use the Keycloak JWKS endpoint for JWT validation, as set by the script in `application.yaml`.
- Disable all internal OAuth2 endpoints.
- Require all `/fhir/*` endpoints to be accessed with Keycloak-issued tokens.

---

## 5. SMART Discovery Endpoint

The `/.well-known/smart-configuration` endpoint will dynamically return Keycloak's endpoints (auth, token, JWKS, registration) when Keycloak is enabled, using values from `config.yaml` and routing through HAProxy.

---

## 6. User Management Delegation

All user management (CRUD, password reset, email verification, etc.) will be delegated to Keycloak via its Admin REST API. The FHIR server will act as a confidential client, authenticating with the client credentials grant using values from `config.yaml`. The FHIR server's user management UI/API will proxy requests to Keycloak or redirect to the Keycloak admin console as appropriate.

---

## 7. Dynamic Client Registration

Dynamic client registration for SMART on FHIR apps will be handled by Keycloak's registration endpoint, which is exposed via HAProxy. The FHIR server will advertise this endpoint in the SMART discovery document. Registration can be restricted to authenticated clients as per Keycloak configuration.

---

## 8. Consent Screen, SSO, and Advanced Features

Keycloak provides a customizable consent screen, SSO, social login, MFA, and more, removing the need for the FHIR server to implement these features. All such flows will be routed through HAProxy for reliability and observability.

---

## 9. Backward Compatibility

If `keycloak.enabled` is `false` or missing, the FHIR server will continue to use the built-in Spring Authorization Server and all existing flows. All configuration and routing changes are conditional and reversible.

---

## 10. Security Considerations

Admin credentials and client secrets will never be hardcoded in Dockerfiles or source code. All sensitive values are loaded from `config.yaml` or environment variables, and passed securely to containers and services. HAProxy can be configured for TLS termination, and all Keycloak endpoints should use HTTPS in production.

---

## 11. Implementation Steps

1. Extend `config.yaml` and the configuration loader for Keycloak options.
2. Enhance `enable-keycloak.sh` to:
   - Update `docker-compose.yaml` and HAProxy config
   - Patch `application.yaml` for Spring Security JWT settings
   - Export environment variables from `config.yaml`
   - Seed Keycloak with initial realm, users, and clients
3. Refactor user management APIs/UI to delegate to Keycloak when enabled.
4. Update SMART discovery endpoint to reflect Keycloak endpoints.
5. Test all flows (OAuth, user CRUD, dynamic registration, SSO, consent, etc.) with Keycloak enabled and disabled.

---

## 12. Design Rationale

This approach is intended to maximize security, flexibility, and maintainability, while minimizing code duplication. Routing all Keycloak traffic through HAProxy ensures consistent, observable, and secure access. All configuration is externalized, and no sensitive data is ever hardcoded. The solution is fully optional and backward-compatible, allowing gradual adoption.

---

## 13. References
- [Keycloak Documentation](https://www.keycloak.org/docs/latest/)
- [SMART on FHIR Dynamic Client Registration](https://hl7.org/fhir/smart-app-launch/)
- [Spring Security Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Keycloak Admin REST API](https://www.keycloak.org/docs-api/24.0.1/rest-api/index.html)

---
