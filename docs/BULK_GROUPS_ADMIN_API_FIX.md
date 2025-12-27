# Bulk Groups Patient Search Fix

## Issue Summary

The Bulk Groups feature in the Admin UI was incorrectly using the **OAuth-secured FHIR API** (`/fhir/Patient/{id}`) to fetch patient data, which required a valid JWT access token. This caused two problems:

1. **Authentication failures**: The UI doesn't obtain OAuth tokens for admin operations, leading to JWT validation errors like "Admin API token JTI is not active"
2. **Wrong error location**: Error messages were displayed on the main panel instead of within the dialog box

## Root Cause

The merged Keycloak PR introduced a "Get by Id" feature that made direct calls to `/fhir/Patient/{id}`, which is the **public FHIR endpoint** protected by SMART on FHIR OAuth scopes. Admin UI operations should use **Admin API endpoints** that query Couchbase directly, similar to how the FHIR Resources module works.

## Solution

### 1. Created New Admin API Endpoint

**File**: `backend/src/main/java/com/couchbase/admin/users/bulkGroup/controller/PatientAdminController.java`

- **New endpoint**: `GET /api/admin/patients/search`
- **Query parameters**:
  - `id`: Fetch a specific patient by ID
  - `query`: Search patients by name
  - `limit`: Maximum results (default 20)

**Key features**:
- Direct Couchbase N1QL queries (no OAuth required)
- Searches in `fhir.Resources.Patient` collection
- Matches on patient ID, name.text, name.given, name.family
- Returns standardized patient info: `{id, name, raw}`

**Example usage**:
```bash
# Fetch by ID
GET /api/admin/patients/search?id=patient-123

# Search by name
GET /api/admin/patients/search?query=john&limit=20
```

### 2. Updated Frontend to Use Admin API

**File**: `frontend/src/pages/BulkGroups/BulkGroups.tsx`

**Changes**:

1. **Added dialog-specific error state**:
   ```typescript
   const [dialogError, setDialogError] = useState<string>("");
   ```

2. **Updated `handleFetchPatientById()` to use Admin API**:
   ```typescript
   // OLD (broken): Direct FHIR API call requiring OAuth token
   const resp = await axios.get(`/fhir/Patient/${encodeURIComponent(patientIdFetch.trim())}`);
   
   // NEW: Admin API call, no OAuth needed
   const resp = await axios.get(`/api/admin/patients/search`, {
     params: { id: patientIdFetch.trim() }
   });
   ```

3. **Updated error handling**:
   ```typescript
   // OLD: setError() → displayed on main panel
   setError("Failed to fetch patient by id");
   
   // NEW: setDialogError() → displayed in dialog
   setDialogError(e?.response?.data?.error || "Failed to fetch patient by id");
   ```

4. **Added error display in dialog UI**:
   ```tsx
   {dialogError && (
     <Box sx={{ mt: 1, p: 1.5, bgcolor: 'error.light', borderRadius: 1 }}>
       <Typography color="error.dark" variant="body2">{dialogError}</Typography>
     </Box>
   )}
   ```

5. **Clear dialog errors on dialog open/reset**:
   - `openCreate()`: Clears `dialogError`
   - `openEdit()`: Clears `dialogError`
   - `resetPatientSearchInputs()`: Clears `dialogError`
   - `closeDialog()`: Clears `dialogError`

6. **Updated patient search error handling**:
   - Changed from `setError()` to `setDialogError()` for consistency

## Architecture Pattern

This fix enforces the correct pattern for Admin UI operations:

### ✅ Correct: Admin API Pattern
```
Admin UI → /api/admin/* → Direct Couchbase Query → Response
```
- No OAuth tokens required
- Direct database access
- Used by: FHIR Resources, Connections, Bulk Groups, etc.

### ❌ Incorrect: FHIR API Pattern (for external apps)
```
External App → /fhir/* → OAuth Token Validation → HAPI FHIR → Couchbase
```
- Requires valid OAuth access token
- Protected by SMART on FHIR scopes
- Used by: Inferno, patient apps, third-party integrations

## Testing

### Backend Build
```bash
cd backend
mvn clean package -DskipTests
# ✅ BUILD SUCCESS
```

### Manual Testing
1. Open Admin UI → Bulk Groups
2. Click "Create Bulk Group"
3. Enter a Patient ID in the "Get by Id" field
4. Click "Fetch"
5. **Expected**: Patient fetched successfully OR error displayed **in dialog** (not main panel)
6. **No JWT warnings** in backend logs

### Expected Backend Log
```
🔍 Fetching patient by ID: patient-123
✅ Found 1 patient(s)
```

### Expected Frontend Behavior
- Valid ID: Patient added to results list
- Invalid ID: Red error box appears **in dialog**: "Patient not found with ID: xyz"
- Network error: Red error box appears **in dialog**: "Failed to search patients: [error message]"

## Files Changed

1. **Backend**:
   - ✅ `backend/src/main/java/com/couchbase/admin/users/bulkGroup/controller/PatientAdminController.java` (NEW)

2. **Frontend**:
   - ✅ `frontend/src/pages/BulkGroups/BulkGroups.tsx` (MODIFIED)

## Benefits

1. **No authentication issues**: Admin API doesn't require OAuth tokens
2. **Better UX**: Errors displayed in the correct location (dialog, not main panel)
3. **Consistent pattern**: Matches FHIR Resources module architecture
4. **Direct database access**: Faster, simpler queries
5. **Proper separation**: Admin operations vs. public FHIR API

## Related Files

- **FHIR Resources Admin Controller**: `backend/src/main/java/com/couchbase/admin/fhirResource/controller/FhirDocumentAdminController.java`
  - Example of correct Admin API pattern: `/api/fhir-resources/document`
  - Direct Couchbase access via `FhirDocumentAdminService`

## Notes

- The patient search function still uses the FHIR API (`/fhir/Patient?name=...`) for advanced filtering with pagination links. This is acceptable since it supports complex FHIR search parameters.
- Future enhancement: Consider moving all patient search operations to Admin API for consistency.
- The "Get by Id" feature description was updated to say "fetch directly from the database" instead of mentioning `/fhir/Patient/{id}`.

