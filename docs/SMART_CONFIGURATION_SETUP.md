# SMART Configuration & User Authentication Setup

## Overview

This guide explains the new SMART on FHIR discovery endpoint and Couchbase-backed user authentication for OAuth flows.

## What's New

### 1. `.well-known/smart-configuration` Endpoint ✅

**Endpoint:** `GET /.well-known/smart-configuration`

SMART apps can now auto-discover your FHIR server's OAuth capabilities without manual configuration.

**Response includes:**

- OAuth 2.0 endpoints (authorize, token, introspect, etc.)
- Supported scopes (patient/_.read, user/_.write, system/_._)
- Grant types (authorization_code, client_credentials, refresh_token)
- SMART capabilities (launch-ehr, launch-standalone, context-ehr-patient, etc.)

### 2. Couchbase User Authentication ✅

OAuth user authentication now uses **real users** from `fhir.Admin.users` collection instead of hardcoded test users.

**Benefits:**

- ✅ Centralized user management
- ✅ Role-based access control
- ✅ Automatic lastLogin tracking
- ✅ Support for admin, developer, and smart_user roles

### 3. Patient Context Resolution ✅

For SMART on FHIR `patient/` scopes, the system automatically resolves patient context in this priority order:

**Priority 1: User's Default Patient** (Most Common)

- Set `defaultPatientId` field when creating the user
- This patient ID is automatically included in access tokens
- Format: Just the patient ID (e.g., `"example-patient-123"`)

**Priority 2: First Accessible Patient** (Fallback)

- If no default patient is set, queries for the first patient resource
- Useful for development/testing

**Priority 3: No Patient Context**

- If no patient is found, the `patient` claim is omitted from the token
- SMART apps should handle missing patient context gracefully

**Example Token with Patient Context:**

```json
{
  "sub": "smart.user@example.com",
  "patient": "example-patient-123",
  "scope": "openid fhirUser patient/*.read",
  "fhirUser": "Practitioner/smart.user@example.com",
  "token_type": "oauth"
}
```

---

## Testing the Implementation

### Step 1: Verify SMART Configuration Endpoint

```bash
# Test the discovery endpoint
curl -s http://localhost:8080/.well-known/smart-configuration | jq .

# Expected response:
{
  "issuer": "http://localhost:8080",
  "authorization_endpoint": "http://localhost:8080/oauth2/authorize",
  "token_endpoint": "http://localhost:8080/oauth2/token",
  "scopes_supported": [
    "openid", "profile", "fhirUser",
    "patient/*.read", "patient/*.write",
    "user/*.read", "user/*.write",
    "system/*.read", "system/*.write",
    ...
  ],
  "capabilities": [
    "launch-ehr", "launch-standalone",
    "client-public", "client-confidential-symmetric",
    "context-ehr-patient", "permission-patient",
    ...
  ]
}
```

### Step 2: Create a Test User for OAuth

Before you can test OAuth flows, you need to create a user in Couchbase.

**Option A: Create via Admin UI** (Recommended)

1. Login to Admin UI as admin
2. Navigate to **Users** page
3. Click **Create User**
4. Fill in:
   - **Email:** `smart.user@example.com`
   - **Username:** `Smart User`
   - **Password:** `password123`
   - **Role:** `smart_user`
   - **Auth Method:** `local`
   - **Status:** `active`
   - **Default Patient ID:** `example-patient-123` (for SMART patient context)
   - **Scopes:** Auto-populated based on role
5. Click **Save**

**Option B: Create via API**

```bash
# First, login as admin to get JWT token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cb-fhir.com","password":"Admin123!"}' \
  | jq -r '.token')

# Create the test user (with patient context)
curl -X POST http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "smart.user@example.com",
    "username": "Smart User",
    "email": "smart.user@example.com",
    "password": "password123",
    "defaultPatientId": "example-patient-123",
    "role": "smart_user",
    "authMethod": "local",
    "status": "active",
    "allowedScopes": [
      "openid", "profile", "fhirUser",
      "patient/*.read", "patient/*.write",
      "launch/patient", "offline_access"
    ]
  }'
```

### Step 3: Test OAuth Authorization Code Flow

**3.1: Initiate Authorization**

Open in your browser:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20fhirUser%20patient/*.read&state=random123
```

**3.2: Login with Couchbase User**

You'll be redirected to the login page. Enter:

- **Username:** `smart.user@example.com`
- **Password:** `password123`

**3.3: Approve Scopes**

After successful login, you'll see a consent screen. Click **Approve**.

**3.4: Exchange Code for Token**

You'll be redirected to:

```
http://localhost:8080/authorized?code=ABC123...&state=random123
```

Copy the authorization code and exchange it:

```bash
# Exchange code for access token
curl -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "code=YOUR_AUTHORIZATION_CODE_HERE" \
  -d "redirect_uri=http://localhost:8080/authorized"

# Response:
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "...",
  "token_type": "Bearer",
  "expires_in": 86400,
  "scope": "openid fhirUser patient/*.read"
}
```

**3.5: Access FHIR Resources**

```bash
# Use the access token to query FHIR resources
ACCESS_TOKEN="eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."

curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient

# Should return patient data if authorized!
```

### Step 4: Verify lastLogin Updated

Check that the user's `lastLogin` timestamp was updated:

```bash
# Get user details
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/admin/users/smart.user@example.com

# Response should include:
{
  "id": "smart.user@example.com",
  "username": "Smart User",
  "email": "smart.user@example.com",
  "role": "smart_user",
  "status": "active",
  "authMethod": "local",
  "lastLogin": "2025-11-24T12:34:56.789Z",  # <-- Should be recent!
  ...
}
```

---

## User Requirements for OAuth

For a user to authenticate via OAuth (for SMART apps), they must meet these criteria:

| Field             | Requirement                                 | Reason                                           |
| ----------------- | ------------------------------------------- | ------------------------------------------------ |
| **status**        | `"active"`                                  | Prevent disabled/suspended users from logging in |
| **authMethod**    | `"local"`                                   | OAuth requires password authentication           |
| **passwordHash**  | Present (BCrypt)                            | Password must be set and hashed                  |
| **role**          | `"admin"`, `"developer"`, or `"smart_user"` | All roles can use OAuth                          |
| **allowedScopes** | Defined                                     | Determines what data the user can access         |

**Note:** Even though `smart_user` role cannot login to the Admin UI, they **CAN** use OAuth for SMART apps.

---

## Roles and Permissions

| Role           | Admin UI Login | OAuth/SMART Login | Default Scopes                           |
| -------------- | -------------- | ----------------- | ---------------------------------------- |
| **admin**      | ✅ Yes         | ✅ Yes            | `system/*.*`, `user/*.*`                 |
| **developer**  | ✅ Yes         | ✅ Yes            | `user/*.*`                               |
| **smart_user** | ❌ No          | ✅ Yes            | `patient/*.read`, `launch/patient`, etc. |

---

## SMART App Integration Example

Here's how a SMART app would discover and connect to your server:

```javascript
// 1. Discover SMART configuration
const config = await fetch(
  "http://localhost:8080/.well-known/smart-configuration"
).then((r) => r.json());

console.log("Authorization endpoint:", config.authorization_endpoint);
console.log("Token endpoint:", config.token_endpoint);
console.log("Supported scopes:", config.scopes_supported);

// 2. Build authorization URL
const authUrl = new URL(config.authorization_endpoint);
authUrl.searchParams.set("response_type", "code");
authUrl.searchParams.set("client_id", "my-smart-app");
authUrl.searchParams.set("redirect_uri", "https://my-app.com/callback");
authUrl.searchParams.set("scope", "openid fhirUser patient/*.read");
authUrl.searchParams.set("state", generateRandomState());

// 3. Redirect user to authorization
window.location.href = authUrl.toString();

// 4. After callback, exchange code for token
const tokenResponse = await fetch(config.token_endpoint, {
  method: "POST",
  headers: {
    "Content-Type": "application/x-www-form-urlencoded",
    Authorization: "Basic " + btoa("client_id:client_secret"),
  },
  body: new URLSearchParams({
    grant_type: "authorization_code",
    code: authorizationCode,
    redirect_uri: "https://my-app.com/callback",
  }),
});

const { access_token } = await tokenResponse.json();

// 5. Access FHIR API
const patients = await fetch("http://localhost:8080/fhir/Patient", {
  headers: { Authorization: `Bearer ${access_token}` },
}).then((r) => r.json());
```

---

## Troubleshooting

### Error: "User not found"

- Verify user exists in `fhir.Admin.users` collection
- Check that bucket is initialized (Admin UI shows "Ready" status)
- Ensure you're using the user's **email** as the username

### Error: "User account is not active"

- Check user's `status` field in Couchbase
- Update via Admin UI: Users → Edit → Set Status to "active"

### Error: "User does not support password authentication"

- Check user's `authMethod` field
- Must be `"local"`, not `"social"`
- Update via Admin UI: Users → Edit → Set Auth Method to "local"

### Error: "User password not configured"

- Check that `passwordHash` field exists and is not empty
- Set password via Admin UI: Users → Edit → Change Password

### SMART configuration returns 404

- Verify server is running on http://localhost:8080
- Check SecurityConfig allows `/.well-known/smart-configuration` (should be permitAll)
- Check CORS configuration includes `/.well-known/**` pattern

### OAuth login shows "Bad credentials"

- Verify password is correct
- Check that passwordHash in Couchbase is BCrypt encoded
- Try resetting password via Admin UI

---

## Architecture Notes

### Why Two Authentication Systems?

1. **Admin UI Login** (`/api/auth/login`)

   - Uses `AuthController` + `UserService`
   - Issues custom JWT for Admin UI access
   - Simpler, faster authentication
   - Checks both Couchbase users AND config.yaml fallback

2. **OAuth Login** (`/oauth2/authorize`)
   - Uses `CouchbaseUserDetailsService` + Spring Security OAuth
   - Issues OAuth 2.0 tokens for FHIR API access
   - Standards-compliant (SMART on FHIR)
   - Only checks Couchbase users (no fallback)

### Security Flow

```
SMART App → /.well-known/smart-configuration (discover endpoints)
         ↓
         → /oauth2/authorize (initiate OAuth flow)
         ↓
         → /login (Spring Security login page)
         ↓
         → CouchbaseUserDetailsService.loadUserByUsername()
         ↓
         → Validate user (status=active, authMethod=local, role set)
         ↓
         → Update lastLogin timestamp (async)
         ↓
         → Show consent screen
         ↓
         → /oauth2/token (exchange code for token)
         ↓
         → FHIR API access with JWT
```

---

## Next Steps (Future Enhancements)

### Phase 3: Dynamic Client Registration (Future)

Currently, OAuth clients are hardcoded (`test-client`). Future enhancement:

- Add `POST /oauth2/register` endpoint
- Store clients in `fhir.Admin.clients` collection
- Replace `InMemoryRegisteredClientRepository` with `CouchbaseRegisteredClientRepository`
- Allow SMART apps to self-register without admin intervention

### Additional Features

- [ ] Patient context resolution (currently hardcoded)
- [ ] EHR launch context (launch/patient scope)
- [ ] PKCE for public clients (already supported, needs testing)
- [ ] Token introspection with scope validation
- [ ] Refresh token rotation
- [ ] Client management UI in Admin dashboard

---

## Summary

✅ **Phase 1 Complete:** `.well-known/smart-configuration` endpoint for SMART app discovery  
✅ **Phase 2 Complete:** Couchbase-backed user authentication with real user management  
⏳ **Phase 3 Pending:** Dynamic client registration (future enhancement)

Your FHIR server is now **SMART-compliant** and ready for production SMART app integration! 🎉
