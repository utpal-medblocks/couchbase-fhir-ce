# Keycloak Token Generation Plan

Goal

- When `app.security.use-keycloak=true` (Keycloak mode), have `TokenService` generate API tokens via Keycloak instead of using the local `JwtEncoder` stub. Keep feature parity with the current SAS/local implementation: same claims, metadata storage, JTI caching, validity period, and scope validation.

High-level approach

- Add a Keycloak-backed token issuer component (e.g. `KeycloakTokenIssuer`) that knows how to request Keycloak to mint an access token representing the API token we want to issue.
- Update `TokenService` to delegate token creation to the `KeycloakTokenIssuer` when Keycloak mode is enabled; keep the existing `JwtEncoder`-based flow when Keycloak is disabled.
- Keep metadata storage and JTI caching unchanged: still create a `Token` metadata object and insert into `fhir.Admin.tokens` and add the JTI to the `JwtTokenCacheService`.

Design considerations / choices

- How to mint tokens in Keycloak:
  - Option A (recommended): Use OAuth 2.0 Token Exchange (grant_type `urn:ietf:params:oauth:grant-type:token-exchange`) between a service account (client credentials) and a subject (the user) when necessary. Token Exchange can produce an access token with requested audience and requested claims depending on configured client scopes/protocol mappers.
  - Option B: Use Keycloak admin API to create an ephemeral client session or impersonate a user (server-side impersonation). This is workable but may be less standard and requires more permissions.
  - Option C: Create a dedicated client (service account) that issues tokens for users by using the `subject_token` or by using client-scopes/protocol mappers to inject custom claims.

- Parity requirements: issued tokens must contain the same claims as the SAS flow (at minimum): `jti`, `sub` (subject/userId), `iat`, `exp`, `scope` (space-separated), `email`, `appName`, and a claim `token_type` with value `api`.
- Signing: in Keycloak mode tokens will be signed by Keycloak. `TokenService` should not attempt to sign tokens locally.
- Scope validation: continue to validate requested scopes locally in `TokenService` (or prior in controller) and only request Keycloak to issue tokens already validated.
- Error handling & logging: return equivalent HTTP error messages to the client (400 on invalid request, 401/403 where appropriate, 500 on server errors). Log admin API/token-exchange errors with clear context.

Risks & notes

- Keycloak configuration is required: service client (with service account), appropriate client scopes and protocol mappers to allow custom claims (`appName`, `token_type`, `jti`) to appear in tokens. Document the required Keycloak-side configuration in `docs/`.
- If Keycloak refuses to issue tokens with arbitrary claims, use a two-step approach: issue a token for a service client and store the token metadata locally with the generated JTI; or use Token Exchange to get a token for the user and augment local metadata as needed.

Implementation tasks

1. Add a small Keycloak admin/token-exchange helper component
   - File: `backend/src/main/java/com/couchbase/admin/users/service/KeycloakTokenIssuer.java` (or under `tokens/service`)
   - Responsibilities:
     - Obtain and cache an admin/service account access token (reuse or mirror logic in `KeycloakUserManagerImpl.obtainAdminToken()`).
     - Perform token-exchange or a token request to Keycloak to mint a token with the desired claims and `exp` matching `api.token.validity.days`.
     - Provide a method `String issueToken(String subject, String[] scopes, String jti, String appName, Instant issuedAt, Instant expiresAt)` which returns a Keycloak-signed JWT string.
     - Provide clear, logged error messages and wrap low-level exceptions into descriptive runtime exceptions.

2. Add/extend application configuration
   - Add a configuration property (if not already present): `app.security.use-keycloak` (already exists) and also add any Keycloak client IDs/secrets or endpoints needed in `application.properties` or `config.yaml` keys (document them).
   - Ensure `KeycloakTokenIssuer` reads relevant properties: `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_TOKEN_ISSUER_CLIENT_ID`, `KEYCLOAK_TOKEN_ISSUER_CLIENT_SECRET` or use service account flow.

3. Update `TokenService` to delegate when Keycloak is enabled
   - Add constructor injection for `KeycloakTokenIssuer` (optional bean) and config property `app.security.use-keycloak`.
   - Branch in `generateToken(...)`: if `useKeycloak == true` then:
     - Validate scopes as before.
     - Build the JTI and timestamps as before.
     - Call `keycloakTokenIssuer.issueToken(...)` with the same claims.
     - Store token metadata and cache JTI exactly like SAS flow.
     - Return `token` string returned by Keycloak and same `tokenMetadata`, `expiresAt`, `validityDays` fields in the response map.
   - Keep existing `JwtEncoder`-based flow unchanged for `useKeycloak == false`.
   - Ensure the overload `generateToken(..., role)` created earlier also follows the same branch.

4. Implement KeycloakTokenIssuer details
   - Token Exchange approach (recommended):
     - Use the service client credentials to make a POST to `/realms/{realm}/protocol/openid-connect/token` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `requested_subject` or `subject_token` if necessary, `requested_token_type` or `audience`.
     - Configure Keycloak: create a client (e.g. `fhir-token-issuer`) and client scope(s) that contain protocol mappers to add `appName`, `token_type`, and `jti` claims. Use mapper `${var}` or script mapper to include `jti` if possible. Alternatively, use a transient user attribute or a custom client scope parameter.
     - If Keycloak cannot add arbitrary jti/appName via mappers during token exchange, consider issuing a token for the service client with a claim that includes the `subject` and `scopes` and store the mapping locally (token metadata + original claim). Document this as a fallback.
   - Alternative simpler approach (if token-exchange mappers are difficult):
     - Use service client to mint a token for the service account, then use Keycloak admin API to create an impersonation token for `subject` and exchange its claims.

5. Tests & validation
   - Unit tests: mock `KeycloakTokenIssuer` and test `TokenService` logic paths for `useKeycloak=true` and `false`, ensuring metadata storage and JTI caching are executed.
   - Integration test (manual instructions):
     - Configure a Keycloak test realm with the recommended client and mappers.
     - Run the backend with `app.security.use-keycloak=true` and attempt to create tokens via `/api/admin/tokens` using a Keycloak user with `admin` role.
     - Confirm the returned token is signed by Keycloak and contains expected claims and that the token metadata is stored in `fhir.Admin.tokens`.

6. Logging and error messages
   - Add informative logs at these points:
     - When choosing Keycloak mode to issue token.
     - When obtaining admin/service token and when it fails.
     - When calling token-exchange endpoint and its HTTP response.
     - When storing token metadata and adding JTI to cache.
   - Ensure returned error messages to the API client match existing patterns (400, 401, 500) with descriptive text.

7. Documentation
   - Add a docs note in `docs/KEYCLOAK_TOKEN_ISSUER.md` (or extend an existing Keycloak doc) describing the Keycloak configuration required (client, mappers, scopes) and example token-exchange requests.
   - Update `plans/Keycloak_Token_Generation_Plan.md` (this file) with any follow-ups or decisions made during implementation.

8. Optional/Follow-ups
   - Add a feature-flagged implementation switch so the operator can toggle between Keycloak issuance and local issuance without redeploying code (e.g. runtime property reloadable via Spring Cloud Config).
   - If Keycloak cannot produce custom jti value, maintain local jti metadata and consider using a Keycloak claim `ext_jti` or store jti in token metadata only.

Concrete implementation tasks (actionable checklist)

- [ ] Create `KeycloakTokenIssuer` component (file: `backend/src/main/java/com/couchbase/admin/tokens/service/KeycloakTokenIssuer.java`) with `issueToken(...)` API and admin token caching.
- [ ] Add configuration properties to `config.yaml.template` and `config.yaml` (documented): `KEYCLOAK_TOKEN_ISSUER_CLIENT_ID`, `KEYCLOAK_TOKEN_ISSUER_CLIENT_SECRET` (or reuse admin credentials) and any `KEYCLOAK_TOKEN_ISSUER_CLIENT_AUDIENCE` if needed.
- [ ] Update `TokenService` to accept `KeycloakTokenIssuer` and `@Value("${app.security.use-keycloak:false}") boolean useKeycloak`. Branch generateToken logic to call Keycloak when `useKeycloak` is true.
- [ ] Ensure `TokenService.generateToken(..., role)` overload follows the same Keycloak flow.
- [ ] Add unit tests to cover both flows.
- [ ] Add documentation for Keycloak configuration and the new runtime behavior.

Estimated time

- Design + Keycloak config doc: 1–2 hours
- Implement `KeycloakTokenIssuer`: 2–4 hours (depends on Keycloak mapper setup complexity)
- Update `TokenService` + unit tests: 1–2 hours
- Integration testing with Keycloak: 1–2 hours (may take longer to configure Keycloak mappers)

Deliverables

- `backend/src/main/java/com/couchbase/admin/tokens/service/KeycloakTokenIssuer.java`
- Updated `TokenService.java` with Keycloak branch
- Configuration docs and `config.yaml.template` updates
- Unit tests and integration instructions

If you want, I can implement this now. I recommend we start with the minimal safe change:

1. Add `KeycloakTokenIssuer` that performs a simple Token Exchange using a service client (client credentials) and requests an access token for the subject using the service account. This preserves the token issuance semantics and keeps changes isolated.
2. Update `TokenService` to call `KeycloakTokenIssuer` when `app.security.use-keycloak=true`.

Which option do you prefer for token minting in Keycloak: Token Exchange (recommended) or Admin impersonation? If unsure, I'll implement Token Exchange and provide Keycloak configuration instructions for required client scopes and protocol mappers.