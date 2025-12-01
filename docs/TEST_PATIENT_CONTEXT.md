# Testing Patient Context Resolution

This guide walks through testing the patient context resolution for SMART on FHIR OAuth flows.

## Prerequisites

1. ✅ FHIR server is running on `http://localhost:8080`
2. ✅ Admin user exists (from `config.yaml`)
3. ✅ FHIR bucket is initialized

---

## Step 1: Create an Example Patient

First, create a patient resource that we can reference:

```bash
# Login as admin
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cb-fhir.com","password":"Admin123!"}' \
  | jq -r '.token')

# Create a test patient
curl -X POST http://localhost:8080/fhir/Patient \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "id": "example-patient-123",
    "identifier": [
      {
        "system": "http://hospital.example.org/patients",
        "value": "12345"
      }
    ],
    "name": [
      {
        "use": "official",
        "family": "Smith",
        "given": ["John"]
      }
    ],
    "gender": "male",
    "birthDate": "1974-12-25",
    "active": true
  }' | jq .

# Expected response:
# {
#   "resourceType": "Patient",
#   "id": "example-patient-123",
#   "name": [{ "family": "Smith", "given": ["John"] }],
#   ...
# }
```

---

## Step 2: Create a SMART User with Default Patient

Create a SMART user that references the patient:

```bash
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
  }' | jq .
```

---

## Step 3: Test OAuth Flow with Patient Context

### 3.1: Initiate Authorization

Open in your browser:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20fhirUser%20patient/*.read&state=test123
```

### 3.2: Login

- **Username:** `smart.user@example.com`
- **Password:** `password123`

### 3.3: Approve Scopes

Review and approve the requested scopes.

### 3.4: Exchange Code for Token

After redirect, you'll be at: `http://localhost:8080/authorized?code=XXX&state=test123`

Exchange the code for an access token:

```bash
CODE="<paste-code-here>"

curl -X POST http://localhost:8080/oauth2/token \
  -u test-client:test-secret \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&code=$CODE&redirect_uri=http://localhost:8080/authorized" \
  | jq .
```

### 3.5: Inspect the Access Token

Decode the access token to verify patient context:

```bash
ACCESS_TOKEN="<paste-access-token-here>"

# Decode JWT (base64 decode the payload)
echo $ACCESS_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .
```

**Expected claims:**

```json
{
  "sub": "smart.user@example.com",
  "aud": "test-client",
  "patient": "example-patient-123", // ← Patient context!
  "scope": "openid fhirUser patient/*.read",
  "fhirUser": "Practitioner/smart.user@example.com",
  "token_type": "oauth",
  "iss": "http://localhost:8080",
  "exp": 1764115546,
  "iat": 1764029146,
  "jti": "..."
}
```

---

## Step 4: Access Patient-Scoped Resources

Use the access token to retrieve the patient's data:

```bash
# Get the patient resource
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient/example-patient-123 | jq .

# Expected: Patient resource returned successfully
```

---

## Patient Context Resolution Logic

The `PatientContextService` resolves patient context in this order:

### Priority 1: User's Default Patient (✅ Implemented)

- **Source:** `user.defaultPatientId` field in `fhir.Admin.users`
- **When used:** Always checked first if set
- **Best for:** Production use, explicit patient assignment

### Priority 2: First Accessible Patient (✅ Implemented)

- **Source:** First `Patient` resource found in the database
- **When used:** Fallback when no default patient is set
- **Best for:** Development/testing

### Priority 3: No Patient Context (✅ Implemented)

- **Behavior:** `patient` claim is omitted from token
- **When happens:** No patient found for user
- **SMART app responsibility:** Handle missing patient context gracefully

---

## Troubleshooting

### Issue: Token has no `patient` claim

**Diagnosis:**

```bash
# Check if user has defaultPatientId set
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cb-fhir.com","password":"Admin123!"}' \
  | jq -r '.token')

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/admin/users/smart.user@example.com | jq .defaultPatientId
```

**Solution:**

- If `null`, update the user to set `defaultPatientId`
- Ensure the patient resource exists in the database

### Issue: Patient not found

**Diagnosis:**

```bash
# Check if patient exists
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/fhir/Patient/example-patient-123
```

**Solution:**

- Create the patient resource (see Step 1)
- Verify the patient ID matches `user.defaultPatientId`

### Issue: Access denied to patient resource

**Diagnosis:**

- Check if token has `patient/*.read` scope
- Verify `SmartAuthorizationInterceptor` is enforcing scopes correctly

**Solution:**

- Ensure user was granted `patient/*.read` scope
- Check Spring Security debug logs for authorization decisions

---

## Future Enhancements (Not in V1.0)

### Query Parameter Support

Allow explicit patient selection via authorization request:

```
http://localhost:8080/oauth2/authorize?
  response_type=code
  &client_id=test-client
  &redirect_uri=http://localhost:8080/authorized
  &scope=patient/*.read
  &patient_id=different-patient-456    // ← Explicit patient
  &state=test123
```

### EHR Launch Context

Support SMART EHR launch with `launch` token:

```
http://localhost:8080/oauth2/authorize?
  response_type=code
  &client_id=test-client
  &redirect_uri=http://localhost:8080/authorized
  &scope=launch
  &launch=eyJhbGciOi...              // ← Launch token with context
  &state=test123
```

### Patient Picker UI

Interactive patient selection during authorization flow.

---

## Summary

✅ **Patient context resolution is now dynamic and configurable**
✅ **Users can have default patients via `defaultPatientId` field**
✅ **Fallback logic handles edge cases gracefully**
✅ **SMART apps receive proper `patient` claim in access tokens**

Next steps: Test with a real SMART app like SMART Health IT's [Growth Chart App](https://growth-chart-app.smarthealthit.org/).
