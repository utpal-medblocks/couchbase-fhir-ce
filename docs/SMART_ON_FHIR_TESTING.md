# SMART on FHIR OAuth 2.0 Testing Guide

This guide provides comprehensive instructions for testing the SMART on FHIR OAuth 2.0 implementation with Spring Authorization Server.

## Overview

The Couchbase FHIR Server now supports SMART on FHIR authorization using OAuth 2.0. This implementation uses:

- **Spring Authorization Server** for OAuth 2.0 endpoints (integrated into the backend)
- **JWT-based access tokens** with SMART scopes
- **Scope-based authorization** enforced at the FHIR resource level
- **Public metadata endpoint** (`/fhir/metadata`) that requires no authentication

## Architecture

### Authentication Layers

1. **Admin UI Authentication** (`/api/admin/*`)

   - Uses custom JWT tokens
   - Separate from FHIR API OAuth
   - Credentials from `config.yaml`

2. **FHIR API OAuth 2.0** (`/fhir/*`)

   - Uses Spring Authorization Server
   - SMART on FHIR scopes
   - JWT access tokens

3. **Public Endpoints**
   - `/fhir/metadata` - FHIR CapabilityStatement (no auth required)
   - `/actuator/**` - Health and metrics endpoints

## OAuth 2.0 Endpoints

| Endpoint                                  | Description                                      |
| ----------------------------------------- | ------------------------------------------------ |
| `/oauth2/authorize`                       | Authorization endpoint (authorization code flow) |
| `/oauth2/token`                           | Token endpoint (exchange code for access token)  |
| `/oauth2/introspect`                      | Token introspection                              |
| `/oauth2/revoke`                          | Token revocation                                 |
| `/oauth2/jwks`                            | JSON Web Key Set                                 |
| `/.well-known/oauth-authorization-server` | OAuth server metadata                            |
| `/login`                                  | Server-side login page for OAuth authentication  |

## SMART Scopes

The following SMART on FHIR scopes are supported:

| Scope             | Description                          |
| ----------------- | ------------------------------------ |
| `openid`          | OpenID Connect authentication        |
| `profile`         | User profile information             |
| `fhirUser`        | FHIR user identifier                 |
| `online_access`   | Realtime access                      |
| `patient/*.read`  | Read all patient resources           |
| `patient/*.write` | Write all patient resources          |
| `user/*.read`     | Read all resources in user context   |
| `user/*.write`    | Write all resources in user context  |
| `system/*.read`   | Read all resources (system context)  |
| `system/*.write`  | Write all resources (system context) |

## Testing OAuth 2.0 Authorization Code Flow

### Prerequisites

- Backend running on `http://localhost:8080`
- `curl` and `jq` installed
- An incognito/private browser window (to avoid session conflicts)

### Step 1: Start the Authorization Flow

Open this URL in an **incognito browser window**:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20patient%2F*.read&state=test123
```

**Parameters**:

- `response_type=code` - Authorization code flow
- `client_id=test-client` - Test client ID
- `redirect_uri` - Where to redirect after authorization (must match registered URI)
- `scope` - Requested scopes (URL-encoded: `openid patient/*.read`)
- `state` - CSRF protection token

### Step 2: Login

You'll be redirected to the login page at `/login`. Use these test credentials:

- **Username**: `fhiruser`
- **Password**: `password`

### Step 3: Authorize (Consent Screen)

After login, you'll see a consent screen asking you to authorize the requested scopes. Click **Submit Consent**.

### Step 4: Get Authorization Code

After authorization, you'll be redirected to:

```
http://localhost:8080/authorized?code=AUTHORIZATION_CODE&state=test123
```

Copy the `AUTHORIZATION_CODE` from the URL.

### Step 5: Exchange Code for Access Token

```bash
curl -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "code=AUTHORIZATION_CODE" \
  -d "redirect_uri=http://localhost:8080/authorized" \
  -d "client_id=test-client" \
  -d "client_secret=test-secret" | jq '.'
```

**Response**:

```json
{
  "access_token": "eyJraWQiOiI...",
  "refresh_token": "...",
  "scope": "patient/*.read openid",
  "token_type": "Bearer",
  "expires_in": 3599
}
```

Save the `access_token` for testing FHIR API calls.

### Step 6: Test FHIR API with Access Token

```bash
# Set the access token
export ACCESS_TOKEN="your_access_token_here"

# Test READ access to Patient resource (should work with patient/*.read scope)
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient | jq '.resourceType'

# Test READ access to Observation resource (should work with patient/*.read scope)
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Observation | jq '.resourceType'

# Test WRITE access (should fail with 403 - insufficient scope)
curl -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Patient","name":[{"family":"Test"}]}' \
  http://localhost:8080/fhir/Patient
```

## Testing Public Endpoints

### Metadata Endpoint (No Authentication Required)

```bash
# Get FHIR CapabilityStatement (no token required)
curl -s http://localhost:8080/fhir/metadata | jq '.resourceType'
```

**Expected**: Should return `"CapabilityStatement"` without requiring authentication.

### FHIR Resources (Authentication Required)

```bash
# Attempt to access Patient without token (should fail with 401)
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" http://localhost:8080/fhir/Patient
```

**Expected**: `HTTP Status: 401`

## Testing Scope Enforcement

### Valid Scope Test

Get a token with `patient/*.read` scope and test READ operations:

```bash
# Authorization URL with patient/*.read scope
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20patient%2F*.read&state=test123
```

After getting the token:

```bash
# Should succeed (scope allows patient resource reads)
curl -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/fhir/Patient
```

### Insufficient Scope Test

Try to WRITE with a READ-only token:

```bash
# Should fail with 403 Forbidden
curl -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Patient","name":[{"family":"Test"}]}' \
  http://localhost:8080/fhir/Patient
```

## Inspecting JWT Tokens

Decode the access token to see its claims:

```bash
# Extract the JWT payload (part between the dots)
echo $ACCESS_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '.'
```

**Expected Claims**:

```json
{
  "sub": "fhiruser",
  "aud": "test-client",
  "scope": ["patient/*.read", "openid"],
  "iss": "http://localhost:8080",
  "exp": 1234567890,
  "iat": 1234567890,
  "patient": "example-patient-123",
  "fhirUser": "Practitioner/fhiruser"
}
```

## OAuth Server Discovery

Get OAuth server metadata:

```bash
curl -s http://localhost:8080/.well-known/oauth-authorization-server | jq '.'
```

**Expected Response**:

```json
{
  "issuer": "http://localhost:8080",
  "authorization_endpoint": "http://localhost:8080/oauth2/authorize",
  "token_endpoint": "http://localhost:8080/oauth2/token",
  "jwks_uri": "http://localhost:8080/oauth2/jwks",
  "revocation_endpoint": "http://localhost:8080/oauth2/revoke",
  "introspection_endpoint": "http://localhost:8080/oauth2/introspect",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token", "client_credentials"],
  "scopes_supported": ["openid", "profile", "fhirUser", "patient/*.read", ...],
  ...
}
```

## Troubleshooting

### Enable Debug Logging

To troubleshoot OAuth issues, enable debug logging in `backend/src/main/resources/logback-spring.xml`:

```xml
<!-- Change from ERROR to DEBUG -->
<logger name="org.springframework.security" level="DEBUG"/>
<logger name="org.springframework.security.oauth2" level="DEBUG"/>
<logger name="com.couchbase.fhir.auth" level="DEBUG"/>
```

Then check logs for:

- `BearerTokenAuthenticationFilter` - JWT token processing
- `JwtAuthenticationToken` - Successful JWT authentication
- `SmartAuthorizationInterceptor` - Scope validation

### Common Issues

1. **404 on `/oauth2/authorize`**

   - Ensure Spring Authorization Server's `applyDefaultSecurity()` is used
   - Check that `AuthorizationServerConfig` is loaded

2. **401 on FHIR API even with valid token**

   - Ensure `fhirFilterChain` uses `new AntPathRequestMatcher("/fhir/**")`
   - Check that `anonymous().disable()` is configured
   - Verify `oauth2ResourceServer().jwt()` is enabled

3. **`invalid_grant` during token exchange**

   - Authorization code is single-use; get a fresh code
   - Ensure `redirect_uri` in token request matches authorization request exactly
   - Use incognito window to avoid session conflicts

4. **AnonymousAuthenticationToken in logs**
   - Ensure `.anonymous(anonymous -> anonymous.disable())` is configured in `fhirFilterChain`
   - Check that JWT is being sent in `Authorization: Bearer` header

## Production Considerations

For production deployments:

1. **Replace In-Memory Storage**

   - Use persistent `RegisteredClientRepository` (database)
   - Store OAuth sessions in Redis/database
   - Use persistent JWT signing keys

2. **Client Management**

   - Dynamic client registration
   - Store clients in Couchbase `fhir.Admin.clients`

3. **User Management**

   - Connect `UserDetailsService` to Couchbase `fhir.Admin.users`
   - Implement proper password policies

4. **Issuer Configuration**

   - Set `app.baseUrl` in `config.yaml` or environment variable `APP_BASE_URL`
   - Ensure issuer URL is publicly accessible (no `localhost`)

5. **Security**
   - Use HTTPS in production
   - Implement rate limiting
   - Add monitoring and audit logging
   - Rotate JWT signing keys regularly

## Next Steps

1. **Implement SMART Launch Context**: Add support for EHR launch and standalone launch
2. **Add More Scopes**: Implement resource-specific scopes (e.g., `patient/Observation.read`)
3. **Patient Context**: Link OAuth sessions to actual patient IDs
4. **Refresh Tokens**: Implement refresh token rotation
5. **Token Introspection**: Add support for opaque tokens
