# FHIR Groups Edit Feature

## Overview
Enhanced the FHIR Groups feature to support editing existing groups and optimized the database queries.

## Changes Implemented

### 1. Backend Optimizations

#### GroupAdminService.java
- **Optimized `getAllGroups()` N1QL Query**: Now uses field-specific extraction instead of fetching full FHIR resources
  ```sql
  SELECT 
    g.name,
    g.id,
    g.quantity,
    (ARRAY e.valueString FOR e IN g.extension WHEN e.url = '...' END)[0] AS memberResourceType,
    (ARRAY e.valueString FOR e IN g.extension WHEN e.url = '...' END)[0] AS creationFilter,
    (ARRAY e.valueString FOR e IN g.extension WHEN e.url = '...' END)[0] AS createdBy,
    (ARRAY e.valueDateTime FOR e IN g.extension WHEN e.url = '...' END)[0] AS lastRefreshed,
    g.type,
    g.meta.lastUpdated
  FROM `fhir`.`Resources`.`General` AS g
  WHERE g.resourceType = 'Group'
  ORDER BY g.meta.lastUpdated DESC
  ```

- **Added `updateGroupFromFilter(id, name, resourceType, filter, createdBy)`**: Re-creates group with same ID (UPSERT)
  - Preserves the existing Group ID
  - Increments the `meta.versionId` (e.g., "1" → "2")
  - Updates `meta.lastUpdated` timestamp
  - Refreshes all members by re-running the filter
  - Updates `lastRefreshed` extension

- **Refactored `createGroupFromFilter()`**: Now uses a shared internal method `createOrUpdateGroupFromFilter()`
  - If `existingId` is `null` → Create new group with generated UUID
  - If `existingId` is provided → Update existing group (UPSERT)

#### GroupAdminController.java
- **Added `PUT /api/admin/groups/{id}`**: Update an existing group
  - Request body: `{ "name": "...", "resourceType": "...", "filter": "..." }`
  - Validates group exists before updating
  - Preserves the original `createdBy` value
  - Returns updated group summary with new member count

- **Enhanced `GET /api/admin/groups/{id}`**: Now splits the filter into `resourceType` and `filter` query parts
  - Before: `filter: "Patient?name=Baxter"`
  - After: `resourceType: "Patient"`, `filter: "name=Baxter"`
  - This makes it easier for the frontend to populate the edit dialog

- **Enhanced logging**: All endpoints now have detailed INFO-level logging for better observability

### 2. Frontend UI Enhancements

#### FHIRGroups.tsx
- **Replaced "Refresh" button with "Edit" button**:
  - **Edit**: Opens the same dialog used for creation, but pre-filled with existing group data
  - User can modify the filter and click "Preview" to see fresh results with updated total count
  - Clicking "Update Group" performs a PUT request, which re-runs the filter and updates the group

- **Added Edit Mode State**:
  - `editingGroupId`: Tracks which group is being edited (null for create mode)
  - Dialog title changes: "Create FHIR Group" vs "Edit FHIR Group"
  - Button text changes: "Create Group" vs "Update Group"

- **Enhanced Table Columns**: Now displays all available group metadata
  - Name
  - Resource Type
  - Filter
  - Members (count)
  - Created By
  - Last Updated (formatted timestamp)
  - Last Refreshed (formatted timestamp or "-")
  - Actions (Edit | Delete)

- **Added `openEditDialog(groupId)`**: Fetches group by ID and populates the dialog
  - Splits the filter into `resourceType` and `filter` parts for the form
  - Sets `editingGroupId` to enable edit mode

- **Updated `handleCreate()`**: Now handles both create and update based on `editingGroupId`
  - If `editingGroupId` is set → Calls `groupService.update()`
  - Otherwise → Calls `groupService.create()`

#### groupService.ts
- **Added `update(id, request)`**: Calls `PUT /api/admin/groups/{id}`

### 3. Database Storage

Groups are stored in **`fhir.Resources.General`** collection with the following structure:

```json
{
  "resourceType": "Group",
  "id": "5bd8d79f-c546-40c7-b489-9326d972f97b",
  "meta": {
    "versionId": "2",
    "lastUpdated": "2025-12-27T20:21:00Z",
    "profile": ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-group"],
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
      "value": "5bd8d79f-c546-40c7-b489-9326d972f97b"
    }
  ],
  "active": true,
  "type": "person",
  "actual": true,
  "name": "Test Group (Updated)",
  "quantity": 5,
  "member": [
    { "entity": { "reference": "Patient/example" } }
    // ... up to 10,000 members
  ],
  "extension": [
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/creation-filter",
      "valueString": "Patient?name=Baxter"
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/created-by",
      "valueString": "admin"
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/last-refreshed",
      "valueDateTime": "2025-12-27T20:21:00Z"
    },
    {
      "url": "http://couchbase.fhir.com/StructureDefinition/member-resource-type",
      "valueString": "Patient"
    }
  ]
}
```

### 4. Key Behaviors

#### UPSERT (Update) Behavior
- When editing a group, the system **does NOT create a new version**
- Instead, it performs an **UPSERT** with the same document key (`Group/[id]`)
- The old version is **overwritten** (no version history is maintained)
- The `meta.versionId` is incremented for tracking purposes

#### Edit Workflow
1. User clicks **Edit** button on a group row
2. System fetches the group details via `GET /api/admin/groups/{id}`
3. Dialog opens with pre-filled values (name, resourceType, filter)
4. User can modify the filter and click **Preview** to see fresh samples and total count
5. User clicks **Update Group**
6. System calls `PUT /api/admin/groups/{id}` with the new parameters
7. Backend re-runs the filter, updates the member list, and stores the group (UPSERT)
8. Table refreshes to show the updated group with new member count and timestamps

## API Endpoints

### PUT /api/admin/groups/{id}
**Update an existing FHIR Group**

**Request:**
```json
{
  "name": "Updated Group Name",
  "resourceType": "Patient",
  "filter": "family=Smith"
}
```

**Response:**
```json
{
  "id": "5bd8d79f-c546-40c7-b489-9326d972f97b",
  "name": "Updated Group Name",
  "filter": "Patient?family=Smith",
  "resourceType": "Patient",
  "memberCount": 42,
  "createdBy": "admin",
  "lastUpdated": "2025-12-27T20:21:00.000Z",
  "lastRefreshed": "2025-12-27T20:21:00.000Z"
}
```

### GET /api/admin/groups/{id}
**Get a specific group by ID (enhanced for editing)**

**Response:**
```json
{
  "id": "5bd8d79f-c546-40c7-b489-9326d972f97b",
  "name": "Test Group",
  "resourceType": "Patient",
  "filter": "name=Baxter",
  "memberCount": 3,
  "createdBy": "admin",
  "lastUpdated": "2025-12-27T10:32:06.000Z",
  "lastRefreshed": "2025-12-27T10:32:06.000Z",
  "type": "person"
}
```

## Benefits

1. **Optimized Database Queries**: Fetching group list is now much faster (only extracts needed fields)
2. **Better User Experience**: Edit workflow is intuitive and uses the same familiar dialog
3. **Live Preview**: Users can see the impact of their filter changes before committing
4. **No Stale Data**: Edit always re-runs the filter to get the latest matching members
5. **Audit Trail**: `meta.versionId` and timestamps track when groups were last modified
6. **FHIR Compliance**: Groups remain valid FHIR resources with proper structure

## Logging

All operations now have comprehensive INFO-level logging:
- `📋 Fetching all groups` → List operation
- `🔍 Querying all Groups from fhir.Resources.General` → N1QL execution
- `✅ Found X groups` → Query result
- `✏️  Update request for group: {id}` → Edit operation
- `✅ Group updated successfully: {id}` → Edit success
- `🗑️  Deleting group: {id}` → Delete operation

## Testing

To test the Edit feature:
1. Create a FHIR Group with a filter (e.g., `Patient?name=Baxter`)
2. Click the **Edit** icon (pencil) on the group row
3. Modify the filter (e.g., change to `Patient?name=Smith`)
4. Click **Preview** to see the new results
5. Click **Update Group** to save the changes
6. Verify the table shows updated member count and "Last Refreshed" timestamp

