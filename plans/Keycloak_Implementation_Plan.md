# Keycloak Implementation Plan

This implementation plan lists all the tasks required to realize the technical design described in `docs/KEYCLOAK_INTEGRATION_PLAN.md`. Each task acts as a ticket. Agents will pick up tickets, add subtasks, and tick them off as completed.

---

## Ticket 1: Extend `config.yaml` for Keycloak
- [x] Add `keycloak` section with all required fields
- [ ] Ensure secure loading of secrets from environment variables
- [ ] Document new config options

## Subtasks
- [x] Add `keycloak` section to `config.yaml` with all required fields (enabled, url, realm, adminUsername, adminPassword, clientId, clientSecret)
- [ ] Ensure secrets are loaded from environment variables or securely from config
- [ ] Update documentation to reflect new config options

## Ticket 2: Enhance `enable-keycloak.sh` Script
- [x] Add Keycloak service to `docker-compose.yaml` (script appends a service block using env interpolation)
- [x] Configure Keycloak to read credentials from environment/config (script writes `.env` keys)
- [x] Update HAProxy config for Keycloak routing (script safely patches `haproxy.cfg`)
- [x] Patch `application.yml` for Keycloak JWKS URI
- [x] Export environment variables for Docker Compose/Spring Boot (script writes to `.env`)
- [x] Optionally seed Keycloak with realm, users, clients (added `scripts/keycloak/seed_keycloak.sh`)

## Subtasks
- [ ] Add Keycloak service to docker-compose.yaml
- [ ] Configure Keycloak to read credentials from environment/config
- [ ] Update HAProxy config for Keycloak routing
- [ ] Patch application.yml for Keycloak JWKS URI
- [ ] Export environment variables for Docker Compose/Spring Boot
- [ ] Optionally seed Keycloak with realm, users, clients

## Ticket 3: Update Spring Security Configuration
 - [x] Modify `application.yml` to use Keycloak JWKS URI
 - [x] Update `SecurityConfig.java` to disable internal OAuth2 endpoints if Keycloak is enabled (AuthorizationServerConfig now conditional)
 - [x] Ensure `/fhir/*` endpoints require Keycloak tokens (resource-server JWT configured via JwtDecoder bean)

## Subtasks
- [ ] Modify application.yml to use Keycloak JWKS URI
- [ ] Update SecurityConfig.java to disable internal OAuth2 endpoints if Keycloak is enabled
- [ ] Ensure /fhir/* endpoints require Keycloak tokens

## Ticket 4: Update SMART Discovery Endpoint
- [x] Make `/.well-known/smart-configuration` dynamic
- [x] Return Keycloak endpoints if enabled
- [x] Route endpoints through HAProxy

## Subtasks
- [x] Make /.well-known/smart-configuration dynamic
- [x] Return Keycloak endpoints if enabled
- [x] Route endpoints through HAProxy

## Ticket 5: Delegate User Management to Keycloak
- [x] Refactor `UserService.java` to proxy user CRUD to Keycloak Admin REST API
- [ ] Update UI/API to redirect or proxy to Keycloak
- [ ] Implement client_credentials grant for admin actions

## Subtasks
- [x] Refactor UserService.java to proxy user CRUD to Keycloak Admin REST API
- [ ] Update UI/API to redirect or proxy to Keycloak
- [ ] Implement client_credentials grant for admin actions

### Implementation notes (completed)
- **Files added/modified:**
	- `backend/src/main/java/com/couchbase/admin/users/service/KeycloakUserManager.java` (interface)
	- `backend/src/main/java/com/couchbase/admin/users/service/KeycloakUserManagerImpl.java` (HTTP-based implementation)
	- `backend/src/main/java/com/couchbase/admin/users/service/UserService.java` (delegates to Keycloak when `app.security.use-keycloak`)
	- `backend/src/main/java/com/couchbase/admin/users/model/User.java` (added transient `passwordPlain`)
	- `scripts/keycloak/test_integration.sh` (integration test helper)

These changes implement a safe, opt-in delegation: when `app.security.use-keycloak` is true and Keycloak beans/credentials are available, `UserService` forwards CRUD operations to Keycloak; otherwise it continues to use Couchbase (SAS).

## Ticket 6: Enable Dynamic Client Registration
- [ ] Advertise Keycloak registration endpoint in SMART config
- [ ] Document registration flow for SMART apps
- [ ] Restrict registration to authenticated clients if needed

## Subtasks
- [ ] Advertise Keycloak registration endpoint in SMART config
- [ ] Document registration flow for SMART apps
- [ ] Restrict registration to authenticated clients if needed

## Ticket 7: Consent Screen, SSO, and Advanced Features
- [ ] Document Keycloak consent, SSO, social login, MFA setup
- [ ] Ensure all flows route through HAProxy

## Subtasks
- [ ] Document Keycloak consent, SSO, social login, MFA setup
- [ ] Ensure all flows route through HAProxy

## Ticket 8: Security Hardening
- [ ] Ensure no secrets are hardcoded in Dockerfiles/source
- [ ] Use HTTPS for Keycloak endpoints in production
- [ ] Document TLS setup for HAProxy/Keycloak

## Subtasks
- [ ] Ensure no secrets are hardcoded in Dockerfiles/source
- [ ] Use HTTPS for Keycloak endpoints in production
- [ ] Document TLS setup for HAProxy/Keycloak

## Ticket 9: Backward Compatibility
- [ ] Ensure fallback to Spring Authorization Server if Keycloak is disabled
- [ ] Make all config/routing changes conditional

## Subtasks
- [ ] Ensure fallback to Spring Authorization Server if Keycloak is disabled
- [ ] Make all config/routing changes conditional

## Ticket 10: Testing & Validation
- [ ] Test all OAuth, user CRUD, registration, SSO, consent flows with Keycloak enabled/disabled
- [ ] Document test cases and results

## Ticket 11: Seed Admin User on Startup (local + Keycloak)
- [x] Seed initial Admin user from `config.yaml` on application start
- [x] When `app.security.use-keycloak` is true, create admin in Keycloak via Admin REST API
- [x] When Keycloak is disabled, create admin user in Couchbase `fhir.Admin.users` collection
- [x] Ensure idempotency: do not recreate existing users
- [x] Document env vars and config required for Keycloak seeding (`KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`)

## Ticket 12: Make disabling keycloak possible
- [ ] Ensure backups are created of `docker-compose*.yml` and `docker-compose*.yaml`, haproxy.cfg and application.yaml are created before keycloak specific configurations are injected
- [ ] Create a script called disable-keycloak.sh that restores the backups of the original files free from any key cloak references
 - [x] Ensure backups are created of `docker-compose*.yml` and `docker-compose*.yaml`, haproxy.cfg and application.yaml are created before keycloak specific configurations are injected
 - [x] Create a script called disable-keycloak.sh that restores the backups of the original files free from any key cloak references

## Subtasks
- [x] Add system property loading from `config.yaml` (admin.email/password/name) (already implemented by `ConfigurationStartupService`)
- [x] Implement startup seeding attempt in `ConfigurationStartupService` after successful auto-connection and initialization (creates user via `UserService`)
- [ ] Add guidance for production Keycloak seeding (see notes below)

### Notes / Plan for Keycloak seeding on Tomcat startup
- Preconditions: Keycloak must be reachable and admin credentials available via env (`KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`).
- Behavior: On startup the backend will call `UserService.createUser(...)`. When Keycloak integration is enabled, `UserService` delegates to `KeycloakUserManager`, which uses the Keycloak Admin REST API to create the user and set the password.
- Idempotency: `KeycloakUserManager` first searches for existing user by username/email and will not duplicate; startup code checks existence and skips creation if present.
- Error handling: If Keycloak is unreachable or admin credentials are invalid, the seeding will log a warning and continue startup (no hard failure).
- Operational recommendation: For production, seed Keycloak once using an infrastructure provisioning step (Terraform/Ansible) or a one-shot init job. Relying on app startup is convenient for dev/test but less ideal for production rock-solid provisioning.


## Subtasks
- [ ] Test all OAuth, user CRUD, registration, SSO, consent flows with Keycloak enabled/disabled
- [ ] Document test cases and results

---

Agents should pick up tickets, break them into subtasks, and tick off completed items. Subtasks and progress will be tracked in this file.
