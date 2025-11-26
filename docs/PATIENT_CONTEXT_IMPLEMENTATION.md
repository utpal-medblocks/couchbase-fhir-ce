# Patient Context Implementation for SMART on FHIR

## Overview

Patient context resolution is now **dynamic and database-driven** instead of hardcoded. The system automatically includes the appropriate patient ID in OAuth access tokens based on user configuration.

---

## What Changed

### Before ❌

```java
// Hardcoded patient context
if (context.getAuthorizedScopes().stream().anyMatch(s -> s.startsWith("patient/"))) {
    context.getClaims().claim("patient", "example-patient-123");  // ← Always the same!
}
```

**Problems:**

- Every user got the same patient context
- No way to configure patient per user
- Required code changes to update patient ID

### After ✅

```java
// Dynamic patient context resolution
if (context.getAuthorizedScopes().stream().anyMatch(s -> s.startsWith("patient/"))) {
    String username = context.getPrincipal().getName();
    String authorizationId = context.getAuthorization() != null ? context.getAuthorization().getId() : null;

    // Resolve patient context using PatientContextService
    String patientId = patientContextService.resolvePatientContext(username, authorizationId);

    if (patientId != null) {
        context.getClaims().claim("patient", patientId);
    }
}
```

**Benefits:**

- ✅ Per-user patient context
- ✅ Database-driven configuration
- ✅ Multiple resolution strategies
- ✅ Graceful fallback handling

---

## Architecture

### New Components

#### 1. `User.defaultPatientId` Field

**Location:** `backend/src/main/java/com/couchbase/admin/users/model/User.java`

```java
/**
 * Optional default patient ID for SMART on FHIR patient context
 * Used when user launches SMART apps without explicit patient selection
 * Format: "patient-id" (just the ID, not "Patient/patient-id")
 */
private String defaultPatientId;
```

**Purpose:** Store user's default patient for SMART OAuth flows

**Database:** `fhir.Admin.users` collection

#### 2. `PatientContextService`

**Location:** `backend/src/main/java/com/couchbase/fhir/auth/service/PatientContextService.java`

**Responsibilities:**

- Resolve patient context from multiple sources
- Validate patient access permissions
- Query FHIR resources when needed

**Key Methods:**

```java
// Resolve patient ID for a user
String resolvePatientContext(String userId, String authorizationId);

// Validate user has access to patient
boolean validatePatientAccess(String patientId, String userId);
```

#### 3. Updated `OAuth2TokenCustomizer`

**Location:** `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java`

**Changes:**

- Injected `PatientContextService`
- Calls service to resolve patient context
- Only adds `patient` claim if context is found

---

## Patient Resolution Strategy

The `PatientContextService` uses a **3-tier resolution strategy**:

### Priority 1: User's Default Patient (Recommended)

**Source:** `user.defaultPatientId` from `fhir.Admin.users`

**When used:** Always checked first if set

**Example:**

```json
{
  "id": "smart.user@example.com",
  "username": "Smart User",
  "defaultPatientId": "example-patient-123", // ← Used here
  "role": "smart_user"
}
```

**Result Token:**

```json
{
  "sub": "smart.user@example.com",
  "patient": "example-patient-123", // ← From user.defaultPatientId
  "scope": "patient/*.read"
}
```

---

### Priority 2: First Accessible Patient (Fallback)

**Source:** First `Patient` resource found in database

**When used:** If user has no `defaultPatientId` set

**Query:**

```sql
SELECT META().id
FROM `fhir`.`default`.`Patient`
LIMIT 1
```

**Result:** Extracts patient ID from document key (e.g., `Patient::patient-123` → `patient-123`)

**Best for:** Development/testing environments

---

### Priority 3: No Patient Context (Graceful Degradation)

**Behavior:** `patient` claim is **omitted** from token

**When happens:**

- User has no `defaultPatientId`
- No patient resources found in database
- Database query fails

**Result Token:**

```json
{
  "sub": "smart.user@example.com",
  "scope": "patient/*.read",
  // ← No "patient" claim
  "fhirUser": "Practitioner/smart.user@example.com"
}
```

**SMART App Responsibility:**

- Must handle missing `patient` context gracefully
- Could prompt user to select a patient
- Could show an error message

---

## Usage Examples

### Creating a User with Patient Context

**Via Admin UI:**

1. Go to Users → Create User
2. Set **Default Patient ID:** `patient-123`
3. Save

**Via API:**

```bash
curl -X POST http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doctor@hospital.com",
    "username": "Dr. Smith",
    "email": "doctor@hospital.com",
    "password": "secure123",
    "defaultPatientId": "patient-789",     // ← Set patient context
    "role": "smart_user",
    "authMethod": "local"
  }'
```

### Updating Patient Context

**Via Admin UI:**

1. Go to Users → Select User → Edit
2. Update **Default Patient ID**
3. Save

**Via API:**

```bash
curl -X PUT http://localhost:8080/api/admin/users/doctor@hospital.com \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "defaultPatientId": "patient-999"    // ← Update patient context
  }'
```

### Testing Patient Context

Follow the comprehensive guide: [`docs/TEST_PATIENT_CONTEXT.md`](./TEST_PATIENT_CONTEXT.md)

---

## Logging & Debugging

The `PatientContextService` provides detailed debug logging:

```
🔍 [PATIENT-CONTEXT] Resolving patient for user: smart.user@example.com
✅ [PATIENT-CONTEXT] Using user's default patient: example-patient-123
```

```
🔍 [PATIENT-CONTEXT] Resolving patient for user: test@example.com
⚠️ [PATIENT-CONTEXT] No patient context found for user: test@example.com
```

**Enable debug logging:**

In `config.yaml`:

```yaml
logging:
  level:
    com.couchbase.fhir.auth.service.PatientContextService: DEBUG
```

---

## Future Enhancements (Not in V1.0)

### 1. Query Parameter Support

Allow explicit patient selection via authorization request:

```
http://localhost:8080/oauth2/authorize?
  ...
  &patient_id=different-patient-456    // ← Override default patient
```

**Implementation:**

- Create `SmartAuthorizationRequestConverter`
- Capture `patient_id` from query params
- Store in `OAuth2Authorization` additional parameters
- Retrieve during token generation

**Status:** Infrastructure ready, needs OAuth2AuthorizationService customization

### 2. EHR Launch Context

Support SMART EHR launch with `launch` token:

```
http://localhost:8080/oauth2/authorize?
  ...
  &scope=launch
  &launch=eyJhbGci...    // ← Launch token contains patient context
```

**Implementation:**

- Decode and validate `launch` token
- Extract `patient` from launch context
- Use for token generation

### 3. Patient Picker UI

Interactive patient selection during OAuth consent:

```
┌─────────────────────────────────┐
│ Select Patient                  │
├─────────────────────────────────┤
│ ○ John Smith (ID: patient-123)  │
│ ○ Jane Doe   (ID: patient-456)  │
│ ○ Bob Jones  (ID: patient-789)  │
│                                 │
│ [Continue]  [Cancel]            │
└─────────────────────────────────┘
```

### 4. Access Control Enforcement

**Current:** Patient context is set, but access is not validated

**Future:**

- Validate user has permission to access patient
- Check organization/practice affiliations
- Enforce data sharing agreements

**Example:**

```java
public boolean validatePatientAccess(String patientId, String userId) {
    // Check if user's organization has access to patient
    // Check if user has appropriate role
    // Check if patient has consented to data sharing
    return hasPermission(userId, patientId);
}
```

---

## Security Considerations

### Current Implementation

✅ **Patient ID is signed in JWT**

- Cannot be tampered with
- Verified by `JwtDecoder` on every request

✅ **SMART scopes are enforced**

- `SmartAuthorizationInterceptor` checks scopes
- Only `patient/*.read` can read patient data

⚠️ **Patient access is NOT validated**

- If user has `defaultPatientId` set, they get that patient
- No check if user has permission to access that patient

### Production Recommendations

1. **Validate Patient Access:**

   - Implement `validatePatientAccess()` in `PatientContextService`
   - Check user's organization/practice affiliations
   - Query patient consent records

2. **Audit Patient Context:**

   - Log all patient context resolutions
   - Track which users accessed which patients
   - Monitor for suspicious patterns

3. **Implement Row-Level Security:**

   - Use Couchbase scopes/collections per organization
   - Enforce data isolation at database level
   - Prevent cross-organization data leaks

4. **Patient Consent Management:**
   - Check patient's data sharing preferences
   - Honor opt-out requests
   - Implement break-the-glass procedures

---

## Testing Checklist

- [x] Create user with `defaultPatientId`
- [x] OAuth flow includes `patient` claim in token
- [x] Token signature verifies successfully
- [x] SMART scopes are enforced
- [ ] Create user without `defaultPatientId` (fallback test)
- [ ] Token omits `patient` claim when none found
- [ ] SMART app handles missing patient context
- [ ] Update user's patient context and verify new tokens

**Run tests:** See [`docs/TEST_PATIENT_CONTEXT.md`](./TEST_PATIENT_CONTEXT.md)

---

## Summary

| Feature                      | Status      | Notes                        |
| ---------------------------- | ----------- | ---------------------------- |
| User.defaultPatientId field  | ✅ Complete | Stored in `fhir.Admin.users` |
| PatientContextService        | ✅ Complete | 3-tier resolution strategy   |
| Dynamic token customization  | ✅ Complete | Replaces hardcoded patient   |
| Fallback to first patient    | ✅ Complete | Development/testing aid      |
| Graceful no-patient handling | ✅ Complete | Omits claim if none found    |
| Query param support          | 🚧 Future   | Infrastructure ready         |
| EHR launch context           | 🚧 Future   | Not in V1.0                  |
| Patient access validation    | 🚧 Future   | Security enhancement         |

**Status:** ✅ **Production-ready for V1.0** (with documented limitations)

**Next Steps:** Test with real SMART apps and gather feedback
