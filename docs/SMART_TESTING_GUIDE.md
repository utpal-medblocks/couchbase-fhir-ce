# SMART on FHIR Testing Guide

This guide shows how to test the OAuth 2.0 / SMART on FHIR implementation.

## Test Client Credentials

**Client ID:** `test-client`  
**Client Secret:** `test-secret`  
**Test User:** `fhiruser` / `password`

## OAuth 2.0 Endpoints

- **Authorization:** `http://localhost:8080/oauth2/authorize`
- **Token:** `http://localhost:8080/oauth2/token`
- **Introspect:** `http://localhost:8080/oauth2/introspect`
- **Revoke:** `http://localhost:8080/oauth2/revoke`
- **JWKS:** `http://localhost:8080/oauth2/jwks`
- **Metadata:** `http://localhost:8080/.well-known/oauth-authorization-server`

## Test Flow 1: Client Credentials (Backend Service)

For backend services without user interaction:

```bash
# Get access token with client credentials
curl -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -d "grant_type=client_credentials&scope=system/*.read"

# Response:
# {
#   "access_token": "eyJhbGc...",
#   "token_type": "Bearer",
#   "expires_in": 3600,
#   "scope": "system/*.read"
# }

# Use the token to access FHIR API
curl -H "Authorization: Bearer <access_token>" \
  http://localhost:8080/fhir/Patient
```

## Test Flow 2: Authorization Code (Interactive)

For web applications with user login:

### Step 1: Get Authorization Code

Open in browser:
```
http://localhost:8080/oauth2/authorize?
  response_type=code&
  client_id=test-client&
  redirect_uri=http://localhost:8080/authorized&
  scope=openid%20profile%20fhirUser%20patient/*.read&
  state=random_state_string
```

User will:
1. Be redirected to login page
2. Enter credentials: `fhiruser` / `password`
3. See consent screen
4. Approve scopes
5. Get redirected with code: `http://localhost:8080/authorized?code=...&state=...`

### Step 2: Exchange Code for Token

```bash
curl -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -d "grant_type=authorization_code&code=<authorization_code>&redirect_uri=http://localhost:8080/authorized"

# Response:
# {
#   "access_token": "eyJhbGc...",
#   "refresh_token": "eyJhbGc...",
#   "token_type": "Bearer",
#   "expires_in": 3600,
#   "scope": "openid profile fhirUser patient/*.read"
# }
```

### Step 3: Use Access Token

```bash
curl -H "Authorization: Bearer <access_token>" \
  http://localhost:8080/fhir/Patient/123
```

## Test Flow 3: Refresh Token

```bash
curl -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -d "grant_type=refresh_token&refresh_token=<refresh_token>"
```

## Check FHIR Metadata

The FHIR metadata endpoint should now advertise SMART capabilities:

```bash
curl http://localhost:8080/fhir/metadata | jq '.rest[0].security'

# Should show:
# {
#   "service": [{
#     "coding": [{
#       "system": "http://terminology.hl7.org/CodeSystem/restful-security-service",
#       "code": "SMART-on-FHIR"
#     }]
#   }],
#   "extension": [{
#     "url": "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris",
#     "extension": [
#       {"url": "token", "valueUri": "http://localhost:8080/oauth2/token"},
#       {"url": "authorize", "valueUri": "http://localhost:8080/oauth2/authorize"},
#       ...
#     ]
#   }]
# }
```

## SMART Scopes

### Patient Context Scopes
- `patient/*.read` - Read all patient data
- `patient/Patient.read` - Read patient demographics
- `patient/Observation.read` - Read observations
- `patient/Condition.read` - Read conditions

### User Context Scopes
- `user/*.read` - Read access as authenticated user
- `user/*.write` - Write access as authenticated user
- `user/Patient.write` - Write patient data

### System Context Scopes
- `system/*.read` - Backend read access
- `system/*.write` - Backend write access

### Launch Scopes
- `openid` - OpenID Connect
- `profile` - User profile
- `fhirUser` - FHIR user reference
- `online_access` - Request refresh token for online access
- `offline_access` - Request refresh token for offline access

## Introspect Token

```bash
curl -X POST http://localhost:8080/oauth2/introspect \
  -u test-client:test-secret \
  -d "token=<access_token>"

# Response:
# {
#   "active": true,
#   "scope": "patient/*.read",
#   "client_id": "test-client",
#   "username": "fhiruser",
#   "exp": 1234567890,
#   ...
# }
```

## Revoke Token

```bash
curl -X POST http://localhost:8080/oauth2/revoke \
  -u test-client:test-secret \
  -d "token=<access_token>"
```

## Common Testing Scenarios

### 1. Test Without Token (Should Fail)
```bash
curl http://localhost:8080/fhir/Patient
# Expected: 401 Unauthorized
```

### 2. Test With Invalid Token (Should Fail)
```bash
curl -H "Authorization: Bearer invalid_token" \
  http://localhost:8080/fhir/Patient
# Expected: 401 Unauthorized
```

### 3. Test With Valid Token (Should Succeed)
```bash
# Get token first
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -d "grant_type=client_credentials&scope=system/*.read" \
  | jq -r '.access_token')

# Use token
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/fhir/Patient
# Expected: 200 OK with patient data
```

### 4. Test Scope Restrictions
```bash
# Get token with limited scope
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -d "grant_type=client_credentials&scope=patient/Observation.read" \
  | jq -r '.access_token')

# Try to access Observation (should work)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/fhir/Observation
# Expected: 200 OK

# Try to access Patient (should fail - no patient/Patient.read scope)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/fhir/Patient
# Expected: 403 Forbidden
```

## Admin UI Still Works

The Admin UI uses its own JWT authentication (not OAuth):

1. Login: `POST /api/auth/login` with `admin@couchbase.com` / `Admin123!`
2. Get JWT token
3. Use token for `/api/admin/*` endpoints

This is separate from SMART/OAuth and continues to work as before.

## Next Steps

1. Implement scope validation in FHIR resource providers
2. Add patient compartment filtering for patient/* scopes
3. Store OAuth clients in Couchbase instead of in-memory
4. Add user management for OAuth users
5. Implement EHR launch flow
6. Add PKCE support for mobile apps

