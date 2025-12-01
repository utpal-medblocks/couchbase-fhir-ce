# OAuth 2.0 Quick Test Guide

## Test Credentials

### Admin UI Login (Custom JWT)
- **URL**: http://localhost:5173/login
- **Email**: admin@couchbase.com
- **Password**: Admin123!

### OAuth 2.0 Login (SMART on FHIR)
- **Username**: fhiruser
- **Password**: password

### OAuth 2.0 Test Client
- **Client ID**: test-client
- **Client Secret**: test-secret
- **Redirect URI**: http://localhost:8080/authorized

---

## Quick OAuth Flow Test

### 1. Get Authorization Code
Open in browser:
```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20profile%20fhirUser%20patient/*.read&state=random_state_string
```

- Login with: `fhiruser` / `password`
- Approve consent screen
- Copy the `code` from the redirect URL

### 2. Exchange Code for Token
```bash
curl -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
  -d "grant_type=authorization_code" \
  -d "code=YOUR_CODE_HERE" \
  -d "redirect_uri=http://localhost:8080/authorized"
```

### 3. Use Access Token
```bash
# Set your access token
export ACCESS_TOKEN="your_access_token_here"

# Test FHIR metadata
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/metadata

# Test FHIR Patient search
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient
```

---

## Discovery Endpoints

### OAuth 2.0 Server Metadata
```bash
curl http://localhost:8080/.well-known/oauth-authorization-server | jq
```

### FHIR CapabilityStatement (includes SMART security info)
```bash
curl http://localhost:8080/fhir/metadata | jq '.rest[0].security'
```

---

## What's Protected

### ✅ Protected with OAuth 2.0 (SMART on FHIR)
- `/fhir/*` - All FHIR API endpoints
- Requires valid OAuth 2.0 access token with SMART scopes

### ✅ Protected with Custom JWT (Admin UI)
- `/api/admin/*` - Admin UI backend endpoints
- Requires JWT from Admin UI login

### ✅ Public Endpoints
- `/actuator/health` - Health check
- `/actuator/metrics` - Metrics
- `/api/auth/login` - Admin UI authentication
- `/oauth2/*` - OAuth endpoints
- `/.well-known/*` - Discovery endpoints
- `/login` - OAuth login page

---

## SMART Scopes Available

- `openid` - OpenID Connect
- `profile` - User profile
- `fhirUser` - FHIR user context
- `launch` - SMART launch context
- `online_access` - Refresh token
- `patient/*.read` - Read all patient resources
- `patient/*.write` - Write all patient resources
- `user/*.read` - Read all user resources (user context)
- `user/*.write` - Write all user resources (user context)
- `system/*.read` - Read all resources (system context)
- `system/*.write` - Write all resources (system context)

---

## Token Contents

The access token includes:
- **Standard Claims**: `sub`, `aud`, `iss`, `exp`, `iat`, `jti`
- **SMART Claims**: 
  - `fhirUser`: e.g., `"Practitioner/fhiruser"`
  - `patient`: e.g., `"example-patient-123"` (if patient scope)
  - `scope`: Array of granted scopes

---

## Troubleshooting

### Admin UI Login Not Working
1. Check backend logs for JWT validation errors
2. Clear browser localStorage: `localStorage.clear()`
3. Verify credentials in `config.yaml`

### OAuth Flow Not Working
1. Check authorization code hasn't expired (1-time use, short-lived)
2. Verify client credentials (test-client:test-secret)
3. Ensure redirect URI matches exactly
4. Check backend logs for OAuth errors

### FHIR API Returns 401
1. Verify access token is not expired (1 hour TTL)
2. Check token has required scopes
3. Use refresh token to get new access token if expired

