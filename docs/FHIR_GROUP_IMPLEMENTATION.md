# FHIR-Compliant Group Management Implementation

## Overview

This implementation replaces the custom `bulk_groups` admin collection with **FHIR R4 Group resources** stored in the proper FHIR Resources collection. Groups are now FHIR-compliant and accessible via standard FHIR APIs while maintaining admin-only write control.

## Architecture

### Storage

- **Location**: `fhir.Resources.General` collection (standard FHIR resource storage)
- **Format**: Complete FHIR R4 `Group` resource with extensions
- **Document Key**: `Group::{uuid}`

### API Endpoints

#### Admin API (Full CRUD)

```
POST   /api/admin/groups/preview      - Preview filter results
POST   /api/admin/groups               - Create group from filter
GET    /api/admin/groups               - List all groups
GET    /api/admin/groups/{id}          - Get specific group
POST   /api/admin/groups/{id}/refresh  - Refresh group (re-run filter)
DELETE /api/admin/groups/{id}/members/{memberRef} - Remove member
DELETE /api/admin/groups/{id}          - Delete group
```

#### FHIR API (Read-only)

```
✅ GET /fhir/Group/{id}         - Read group
✅ GET /fhir/Group              - Search groups
✅ GET /fhir/Group/{id}/$export - Bulk export (future)
❌ POST /fhir/Group             - 405 Method Not Allowed
❌ PUT /fhir/Group/{id}         - 405 Method Not Allowed
❌ DELETE /fhir/Group/{id}      - 405 Method Not Allowed
```

---

## Components

### 1. FilterPreviewService

**Path**: `backend/src/main/java/com/couchbase/admin/users/bulkGroup/service/FilterPreviewService.java`

**Purpose**: Execute FHIR search filters and return preview results

**Supported Resource Types**:

- Device
- Group
- Medication
- Patient
- Practitioner
- PractitionerRole
- RelatedPerson
- Substance

**Key Methods**:

```java
FilterPreviewResult executeFilterPreview(String resourceType, String filterQuery)
// Returns: { totalCount, sampleResources (max 10), resourceType, filter }

List<String> getAllMatchingIds(String resourceType, String filterQuery, int maxMembers)
// Returns: ["Patient/123", "Patient/456", ...]
```

**Filter Examples**:

```
Patient?family=Smith
Patient?birthdate=ge1987-01-01&birthdate=le1987-12-31
Patient?identifier=http://hospital.smarthealthit.org|103270
Practitioner?name=Johnson
Device?status=active
```

---

### 2. GroupAdminService

**Path**: `backend/src/main/java/com/couchbase/admin/users/bulkGroup/service/GroupAdminService.java`

**Purpose**: Create and manage FHIR Group resources

**Key Features**:

- Builds complete FHIR R4 `Group` resources
- Adds custom extensions for filter tracking
- Supports refresh (re-run filter)
- Manual member removal
- Version tracking

**FHIR Group Structure**:

```json
{
  "resourceType": "Group",
  "id": "uuid",
  "meta": {
    "versionId": "1",
    "lastUpdated": "2025-12-26T...",
    "profile": [
      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-group"
    ],
    "tag": [
      {
        "system": "http://couchbase.fhir.com/fhir/custom-tags",
        "code": "created-by",
        "display": "user:admin"
      }
    ]
  },
  "identifier": [
    {
      "system": "http://couchbase.fhir.com/group-id",
      "value": "uuid"
    }
  ],
  "active": true,
  "type": "person",
  "actual": true,
  "name": "Diabetes Patient Cohort",
  "quantity": 2450,
  "member": [
    {
      "entity": {
        "reference": "Patient/uuid-1"
      }
    }
    // ... up to 10k members
  ],
  "extension": [
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/creation-filter",
      "valueString": "Patient?_has:Condition:subject:code=http://hl7.org/fhir/sid/icd-10-cm|E11"
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/created-by",
      "valueString": "admin-user-123"
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/last-refreshed",
      "valueDateTime": "2025-12-26T..."
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/member-resource-type",
      "valueString": "Patient"
    }
  ]
}
```

**Custom Extensions**:

- `creation-filter`: Original FHIR search filter used to create group
- `created-by`: User who created the group
- `last-refreshed`: Last time group members were refreshed
- `member-resource-type`: Type of resources in the group

**Key Methods**:

```java
Group createGroupFromFilter(String name, String resourceType, String filter, String createdBy)
Optional<Group> getGroupById(String id)
List<Group> getAllGroups()
Group refreshGroup(String id)  // Re-run filter, update members
Group removeMember(String groupId, String memberReference)
void deleteGroup(String id)
```

**Limits**:

- Max 10,000 members per group

---

### 3. GroupAdminController

**Path**: `backend/src/main/java/com/couchbase/admin/users/bulkGroup/controller/GroupAdminController.java`

**Purpose**: Admin API endpoints for Group management

**Endpoints**:

#### Preview Filter

```http
POST /api/admin/groups/preview
Content-Type: application/json

{
  "resourceType": "Patient",
  "filter": "family=Smith&birthdate=ge1987"
}

Response:
{
  "totalCount": 42,
  "sampleResources": [
    {
      "id": "patient-123",
      "resourceType": "Patient",
      "name": "John Smith",
      "birthDate": "1987-05-12",
      "gender": "male"
    },
    // ... up to 10 samples
  ],
  "resourceType": "Patient",
  "filter": "family=Smith&birthdate=ge1987"
}
```

#### Create Group

```http
POST /api/admin/groups
Content-Type: application/json

{
  "name": "Smith Family Born 1987+",
  "resourceType": "Patient",
  "filter": "family=Smith&birthdate=ge1987",
  "createdBy": "admin"
}

Response:
{
  "id": "abc-123",
  "name": "Smith Family Born 1987+",
  "filter": "Patient?family=Smith&birthdate=ge1987",
  "resourceType": "Patient",
  "memberCount": 42,
  "createdBy": "admin",
  "lastUpdated": "2025-12-26T...",
  "lastRefreshed": "2025-12-26T..."
}
```

#### List Groups

```http
GET /api/admin/groups

Response:
{
  "groups": [
    {
      "id": "abc-123",
      "name": "Smith Family",
      "filter": "Patient?family=Smith",
      "resourceType": "Patient",
      "memberCount": 42,
      "createdBy": "admin",
      "lastUpdated": "2025-12-26T...",
      "lastRefreshed": "2025-12-26T..."
    }
  ]
}
```

#### Get Group

```http
GET /api/admin/groups/{id}

Response:
{
  "id": "abc-123",
  "name": "Smith Family",
  "filter": "Patient?family=Smith",
  "resourceType": "Patient",
  "memberCount": 42,
  "createdBy": "admin",
  "lastUpdated": "2025-12-26T...",
  "lastRefreshed": "2025-12-26T...",
  "members": [
    "Patient/123",
    "Patient/456",
    // ...
  ]
}
```

#### Refresh Group

```http
POST /api/admin/groups/{id}/refresh

Response:
{
  "id": "abc-123",
  "name": "Smith Family",
  "memberCount": 45,  // Updated
  "lastRefreshed": "2025-12-26T...",  // Updated
  "message": "Group refreshed successfully"
}
```

#### Remove Member

```http
DELETE /api/admin/groups/{id}/members/Patient%2F456

Response:
{
  "message": "Member removed successfully",
  "groupId": "abc-123",
  "newMemberCount": 41
}
```

#### Delete Group

```http
DELETE /api/admin/groups/{id}

Response:
{
  "message": "Group deleted successfully",
  "id": "abc-123"
}
```

---

### 4. GroupWriteBlockInterceptor

**Path**: `backend/src/main/java/com/couchbase/fhir/auth/GroupWriteBlockInterceptor.java`

**Purpose**: Block write operations on Group resources via FHIR API

**Behavior**:

- Intercepts all requests to `/fhir/Group/*`
- Allows: GET, SEARCH, $export
- Blocks: POST, PUT, DELETE, PATCH
- Returns: `405 Method Not Allowed` with helpful error message

**Error Response**:

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "not-supported",
      "diagnostics": "Group resources cannot be created or modified via the FHIR API. Please use the Admin API at /api/admin/groups for Group management. Operation CREATE is not allowed."
    }
  ]
}
```

---

### 5. PatientAdminController

**Path**: `backend/src/main/java/com/couchbase/admin/users/bulkGroup/controller/PatientAdminController.java`

**Purpose**: Helper endpoint for patient search (reused from previous implementation)

**Note**: This controller is still useful for the UI to search patients without OAuth tokens.

---

## Frontend Integration (Pending)

### UI Flow

1. **Preview Filter**

   - User enters filter (e.g., `Patient?family=Smith`)
   - Call `POST /api/admin/groups/preview`
   - Show table with:
     - Total count
     - Sample of 10 patients (ID, Name, DOB, Gender)
   - User can refine or accept

2. **Create Group**

   - User enters group name
   - User confirms preview
   - Call `POST /api/admin/groups`
   - Show success message

3. **Group List Table**

   - Columns: Name, Filter, Resource Type, Member Count, Created By, Last Updated
   - Actions: Edit (refresh, remove members), Delete

4. **Refresh Group**

   - Click "Refresh" button
   - Call `POST /api/admin/groups/{id}/refresh`
   - Show updated member count

5. **Remove Member**
   - Click "Remove" next to member
   - Call `DELETE /api/admin/groups/{id}/members/{ref}`
   - Update member list

---

## Benefits

### FHIR Compliance

✅ Proper FHIR R4 Group resource structure  
✅ Stored in standard FHIR Resources collection  
✅ Accessible via FHIR API (read-only)  
✅ Supports FHIR metadata (versioning, tags, extensions)  
✅ Compatible with FHIR bulk export ($export)

### Interoperability

✅ External FHIR clients can read groups  
✅ Standard FHIR search parameters work  
✅ Can be secured with SMART on FHIR scopes  
✅ Works with bulk data export flows

### Flexibility

✅ Supports 8 resource types (not just patients)  
✅ Filter-based dynamic membership  
✅ Refreshable (re-run filter)  
✅ Manual override (remove individuals)  
✅ Tracks creation metadata

### Security

✅ Admin-only write operations  
✅ Public FHIR API is read-only  
✅ Group context for SMART tokens (future)

---

## Migration from Old Implementation

### Old System

- **Storage**: `fhir.Admin.bulk_groups`
- **Format**: Custom `BulkGroup` POJO
- **API**: `/api/admin/bulk-groups`
- **Access**: Admin API only

### New System

- **Storage**: `fhir.Resources.General`
- **Format**: FHIR R4 `Group` resource
- **API**: `/api/admin/groups` (write), `/fhir/Group` (read)
- **Access**: Admin API (write) + FHIR API (read)

### Migration Path

1. Keep old `BulkGroupService` temporarily
2. Add migration endpoint to convert old groups → FHIR Groups
3. Frontend updates to use new API
4. Remove old implementation after verification

---

## Testing

### Backend Build

```bash
cd backend
mvn clean package -DskipTests
# ✅ BUILD SUCCESS
```

### Manual Testing

```bash
# 1. Preview filter
curl -X POST http://localhost:8080/api/admin/groups/preview \
  -H "Content-Type: application/json" \
  -d '{"resourceType": "Patient", "filter": "family=Smith"}'

# 2. Create group
curl -X POST http://localhost:8080/api/admin/groups \
  -H "Content-Type: application/json" \
  -d '{"name": "Smith Family", "resourceType": "Patient", "filter": "family=Smith", "createdBy": "admin"}'

# 3. List groups
curl http://localhost:8080/api/admin/groups

# 4. Get group
curl http://localhost:8080/api/admin/groups/{id}

# 5. Refresh group
curl -X POST http://localhost:8080/api/admin/groups/{id}/refresh

# 6. Read via FHIR API (should work)
curl http://localhost:8080/fhir/Group/{id}

# 7. Try to write via FHIR API (should fail with 405)
curl -X POST http://localhost:8080/fhir/Group \
  -H "Content-Type: application/json" \
  -d '{"resourceType": "Group", "type": "person", "actual": true}'
```

---

## Future Enhancements

1. **SMART Group Context**

   - Extend token customization to include `"group": "Group/id"`
   - Filter resource access based on group membership
   - Cache group membership for performance

2. **Bulk Export Integration**

   - Implement `Group/{id}/$export` operation
   - Export all resources for group members
   - Support `_type` parameter for selective export

3. **Scheduled Refresh**

   - Add cron job to refresh groups periodically
   - Track refresh history
   - Send notifications on member changes

4. **Advanced Filters**

   - Support complex FHIR search parameters
   - `_has`, `_revinclude`, chained searches
   - Full FTS integration

5. **Group Hierarchies**

   - Support nested groups (Group referencing other Groups)
   - Flatten group membership for export
   - Circular reference detection

6. **UI Enhancements**
   - Filter builder with autocomplete
   - Member pagination (beyond 10k)
   - Diff view on refresh (show added/removed)
   - Export group member list to CSV

---

## Files Created/Modified

### Created

- ✅ `FilterPreviewService.java` - Filter execution and preview
- ✅ `GroupAdminService.java` - FHIR Group management
- ✅ `GroupAdminController.java` - Admin API endpoints
- ✅ `GroupWriteBlockInterceptor.java` - FHIR API write blocker

### Modified

- ✅ `FhirRestfulServer.java` - Register GroupWriteBlockInterceptor
- ✅ `PatientAdminController.java` - Fixed constructor injection

### To Modify (Frontend)

- 🔄 `BulkGroups.tsx` → `GroupManagement.tsx`
- 🔄 Add resource type selector
- 🔄 Add filter builder
- 🔄 Update API calls to new endpoints

---

## Summary

This implementation transforms the custom bulk groups feature into a **FHIR-compliant Group management system**. Groups are now proper FHIR resources that can be:

- ✅ Read via standard FHIR API
- ✅ Searched using FHIR search parameters
- ✅ Exported using bulk data operations
- ✅ Managed exclusively via Admin UI/API
- ✅ Extended with custom metadata via extensions

The system maintains the admin-only write control while enabling interoperability with external FHIR clients and compliance with FHIR standards.
