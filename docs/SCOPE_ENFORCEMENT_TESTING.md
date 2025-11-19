# SMART Scope Enforcement Testing Guide

## Overview

This guide demonstrates how scope enforcement works in the Couchbase FHIR Server. After implementing SMART authorization, all FHIR API requests (`/fhir/*`) require valid OAuth 2.0 tokens with appropriate scopes.

---

## Testing Setup

### Prerequisites
1. Backend server running on `http://localhost:8080`
2. OAuth test client configured (test-client/test-secret)
3. Test user: `fhiruser` / `password`

---

## Test Scenarios

### Scenario 1: Full Access Token (Read All Resources)

**Get token with patient/*.read scope:**
```bash
# Step 1: Get authorization code
# Open in browser:
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=patient/*.read&state=test1

# Step 2: Login and approve, then exchange code for token
CODE="<your_code_here>"
TOKEN_RESPONSE=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
  -d "grant_type=authorization_code" \
  -d "code=$CODE" \
  -d "redirect_uri=http://localhost:8080/authorized")

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token')
echo "Access Token: $ACCESS_TOKEN"
```

**Test READ operations (Should succeed):**
```bash
# Read Patient
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient | jq '.resourceType, .total'

# Read Observation
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Observation | jq '.resourceType, .total'

# Read specific Patient
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient/example | jq '.resourceType, .id'
```

**Expected:** ✅ All requests succeed, returning FHIR resources

**Test WRITE operations (Should fail):**
```bash
# Try to create a Patient
curl -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "name": [{"family": "Test", "given": ["John"]}]
  }' \
  http://localhost:8080/fhir/Patient
```

**Expected:** ❌ `403 Forbidden` or `AuthenticationException` - "Insufficient scope: Patient.write"

---

### Scenario 2: Resource-Specific Token (Only Observations)

**Get token with patient/Observation.read scope:**
```bash
# Open in browser:
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=patient/Observation.read&state=test2

# Exchange code for token (same process as above)
```

**Test Observation READ (Should succeed):**
```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Observation | jq '.resourceType, .total'
```

**Expected:** ✅ Returns Observation resources

**Test Patient READ (Should fail):**
```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient
```

**Expected:** ❌ `403 Forbidden` - "Insufficient scope: Patient.read operation requires appropriate SMART scope"

---

### Scenario 3: Write Token (Create/Update)

**Get token with user/*.write scope:**
```bash
# Open in browser:
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=user/*.write&state=test3

# Exchange code for token
```

**Test CREATE operations (Should succeed):**
```bash
# Create a new Patient
curl -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "name": [{"family": "Smith", "given": ["Alice"]}],
    "gender": "female",
    "birthDate": "1990-05-15"
  }' \
  http://localhost:8080/fhir/Patient | jq '.resourceType, .id'
```

**Expected:** ✅ Creates patient, returns `201 Created` with patient ID

**Test UPDATE operations (Should succeed):**
```bash
# Update the patient
PATIENT_ID="<patient_id_from_create>"
curl -X PUT -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "id": "'$PATIENT_ID'",
    "name": [{"family": "Smith", "given": ["Alice", "Marie"]}],
    "gender": "female",
    "birthDate": "1990-05-15"
  }' \
  http://localhost:8080/fhir/Patient/$PATIENT_ID | jq '.resourceType, .id'
```

**Expected:** ✅ Updates patient, returns `200 OK`

---

### Scenario 4: No Scope Token (Should fail all operations)

**Get token with only openid scope:**
```bash
# Open in browser:
http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid&state=test4

# Exchange code for token
```

**Test any FHIR operation (Should fail):**
```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient
```

**Expected:** ❌ `403 Forbidden` - "Insufficient scope: Patient.read operation requires appropriate SMART scope"

---

### Scenario 5: System Scope (Backend-to-Backend)

**Get token using Client Credentials flow:**
```bash
TOKEN_RESPONSE=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
  -d "grant_type=client_credentials" \
  -d "scope=system/*.read")

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token')
```

**Test system-level access (Should succeed):**
```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient | jq '.resourceType, .total'
```

**Expected:** ✅ Returns all patients (system scope bypasses patient filtering)

---

## Metadata Endpoint (Always Public)

**Test metadata without authentication:**
```bash
curl http://localhost:8080/fhir/metadata | jq '.resourceType, .status'
```

**Expected:** ✅ Returns CapabilityStatement without requiring authentication

---

## Scope Validation Logic

### Read Operations
Required scopes (any one):
- `patient/<ResourceType>.read`
- `patient/*.read`
- `user/<ResourceType>.read`
- `user/*.read`
- `system/<ResourceType>.read`
- `system/*.read`

### Write Operations
Required scopes (any one):
- `patient/<ResourceType>.write`
- `patient/*.write`
- `user/<ResourceType>.write`
- `user/*.write`
- `system/<ResourceType>.write`
- `system/*.write`

---

## Troubleshooting

### Issue: Getting 401 Unauthorized
**Cause:** Token is expired or invalid
**Solution:** Get a new token (authorization codes expire quickly)

### Issue: Getting 403 Forbidden with scope error
**Cause:** Token doesn't have required scope
**Solution:** Request a new authorization code with the correct scopes

### Issue: Token works but returns empty results
**Cause:** Patient-scoped token may be filtering results
**Solution:** Check the `patient` claim in your JWT - it might be limiting to a specific patient ID

---

## Verifying Scope Enforcement

### Check Backend Logs
Look for these log messages:
```
✅ Authorized: fhiruser for read Patient
🚫 Access denied: fhiruser attempted write on Patient without proper scopes
```

### Inspect Token Claims
Decode your JWT to see what scopes it contains:
```bash
echo "$ACCESS_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '.scope'
```

Expected output:
```json
[
  "patient/*.read",
  "openid",
  "profile",
  "fhirUser"
]
```

---

## Testing Checklist

- [ ] Read with patient/*.read - succeeds for all resource types
- [ ] Read with patient/Observation.read - succeeds only for Observation
- [ ] Write with patient/*.read - fails (insufficient scope)
- [ ] Write with user/*.write - succeeds
- [ ] Read with no FHIR scopes - fails
- [ ] Metadata access without auth - succeeds
- [ ] System scope via client credentials - succeeds

---

## Production Considerations

1. **Scope Granularity**: Consider implementing per-resource scopes in production
2. **Patient Context**: Implement patient ID filtering for patient-scoped requests
3. **Audit Logging**: Log all authorization failures for security monitoring
4. **Rate Limiting**: Add rate limiting to prevent token abuse
5. **Token Rotation**: Implement refresh token rotation for enhanced security

---

## Summary

✅ **Scope enforcement is now active!**

Every FHIR API request must include:
1. Valid OAuth 2.0 access token
2. Appropriate SMART scope for the resource and operation

This ensures that:
- Third-party apps can only access data they're authorized for
- Read and write operations are properly separated
- Patient data is protected by granular scopes
- System-level access is reserved for trusted backend services

