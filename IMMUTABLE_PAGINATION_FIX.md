# Immutable Pagination State Fix

## Problem

After removing document updates to keep pagination state immutable (write-once, read-many), the offset wasn't advancing in the "next" links because:

1. **Document offset never updated**: `currentOffset` in Couchbase document stayed at 0
2. **Code used document offset**: Logic was calling `paginationState.getNextPageKeys()` which used the internal offset
3. **URL offset ignored**: The `_offset` parameter in continuation URLs wasn't being used

## Root Cause

The code was designed to:

- **Update** the offset in the document after each page
- **Read** that updated offset for the next page

But after making documents immutable, we:

- ✅ Stopped updating the document (good!)
- ❌ Forgot to use the offset from the URL parameter (bad!)

## Solution

Changed pagination to be **truly stateless**:

### 1. Pass offset/count from URL to pagination handlers

**SearchService.java:**

```java
// Before: Method didn't receive offset/count
private Bundle handleContinuationTokenNewPagination(
    String continuationToken, String resourceType, ...)

// After: Method receives offset/count from URL
private Bundle handleContinuationTokenNewPagination(
    String continuationToken, String resourceType,
    int offset, int count, ...)  // ← Added parameters
```

### 2. Use URL offset to get page keys

**Before:**

```java
// Used internal offset (always 0)
List<String> currentPageKeys = paginationState.getNextPageKeys();
```

**After:**

```java
// Use offset from URL parameter
List<String> allDocumentKeys = paginationState.getAllDocumentKeys();
int pageSize = count > 0 ? count : paginationState.getPageSize();
int fromIndex = Math.min(offset, allDocumentKeys.size());
int toIndex = Math.min(offset + pageSize, allDocumentKeys.size());
List<String> currentPageKeys = allDocumentKeys.subList(fromIndex, toIndex);
```

### 3. Calculate next offset from current parameters

**Before:**

```java
// Used stored offset (wrong after we stopped updating)
if (paginationState.hasMoreResults()) {
    buildNextPageUrl(..., paginationState.getCurrentOffset(), ...);
}
```

**After:**

```java
// Calculate next offset from current page
int nextOffset = offset + currentPageKeys.size();
boolean hasMoreResults = nextOffset < allDocumentKeys.size();

if (hasMoreResults) {
    buildNextPageUrl(..., nextOffset, ...);
}
```

### 4. Updated $everything operation

Added `_offset` parameter to the operation:

```java
@Operation(name = "$everything", idempotent = true, type = Patient.class)
public Bundle patientEverything(
        @IdParam IdType theId,
        ...
        @OperationParam(name = "_count") IntegerType count,
        @OperationParam(name = "_page") StringParam page,
        @OperationParam(name = "_offset") IntegerType offset,  // ← Added
        RequestDetails requestDetails) {
```

Updated `EverythingService.getPatientEverythingNextPage()` to use offset/count from parameters.

## Files Changed

1. **SearchService.java**

   - `handleContinuationTokenNewPagination()` - Added offset/count parameters, use URL offset for slicing
   - `handleRevIncludePagination()` - Pass offset/count to handler

2. **FhirCouchbaseResourceProvider.java**

   - `patientEverything()` - Added `_offset` parameter
   - `buildEverythingContinuationBundle()` - Added offset/count parameters, use for next link

3. **EverythingService.java**
   - `getPatientEverythingNextPage()` - Added offset/count parameters, use URL offset for slicing

## How It Works Now

### Pagination Flow

```
┌─────────────────────────────────────────────────────────────┐
│ Initial Request: GET /Patient?_count=10                     │
└─────────────────────────────────────────────────────────────┘
                          ↓
    ┌──────────────────────────────────────────────┐
    │ 1. FTS: Get up to 1000 document keys         │
    │ 2. Store in Couchbase Admin.cache (rev 1)   │
    │    - allDocumentKeys: [...1000 keys...]      │
    │    - currentOffset: 0 (unused)               │
    │ 3. Return first 10 resources                 │
    │ 4. Next link: ?_page=abc&_offset=10&_count=10│
    └──────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Page 2: GET /Patient?_page=abc&_offset=10&_count=10        │
└─────────────────────────────────────────────────────────────┘
                          ↓
    ┌──────────────────────────────────────────────┐
    │ 1. Read document from Couchbase (no write)   │
    │ 2. Get keys: allDocumentKeys[10:20]          │
    │ 3. KV: Fetch 10 resources                    │
    │ 4. Next link: ?_page=abc&_offset=20&_count=10│
    └──────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Page 3: GET /Patient?_page=abc&_offset=20&_count=10        │
└─────────────────────────────────────────────────────────────┘
                          ↓
    ┌──────────────────────────────────────────────┐
    │ 1. Read document from Couchbase (no write)   │
    │ 2. Get keys: allDocumentKeys[20:30]          │
    │ 3. KV: Fetch 10 resources                    │
    │ 4. Next link: ?_page=abc&_offset=30&_count=10│
    └──────────────────────────────────────────────┘
                          ↓
                       (continues)

After 180 seconds: Document expires (TTL from creation)
```

### Couchbase Document (Never Changes!)

```json
{
  "id": "abc123",
  "rev": "1-...",              ← Only revision, never updated
  "expiration": 1761252421,    ← TTL honored from creation
  "allDocumentKeys": [         ← The important part
    "Patient/1",
    "Patient/2",
    ...
    "Patient/1000"
  ],
  "bucketName": "acme",
  "baseUrl": "http://localhost:8080/fhir/acme",
  "searchType": "regular",
  "resourceType": "Patient",
  "pageSize": 10,
  "currentOffset": 0,          ← Unused (kept for compatibility)
  "createdAt": "2025-10-23T13:00:00",
  "expiresAt": "2025-10-23T13:03:00"
}
```

**The document is write-once, read-many:**

- Created once with all keys
- Never updated (no writes after creation)
- Offset tracked in URL, not document
- TTL expires 180 seconds from creation

## Benefits

1. ✅ **Truly immutable** - Document never changes after creation
2. ✅ **TTL honored** - Expires exactly 180 seconds from creation (no resets)
3. ✅ **75% fewer writes** - 1 write per search (was 4 writes for 4-page search)
4. ✅ **Stateless pagination** - All state in URL, document only stores keys
5. ✅ **Works correctly** - Offset advances properly in "next" links

## Testing

Test all pagination types:

```bash
# 1. Regular search
GET /fhir/acme/Patient?_count=10
GET /fhir/acme/Patient?_page=TOKEN&_offset=10&_count=10  # Check offset advances
GET /fhir/acme/Patient?_page=TOKEN&_offset=20&_count=10  # Check again

# 2. _revinclude
GET /fhir/acme/Patient?_count=10&_revinclude=Observation:patient
GET /fhir/acme/Patient?_page=TOKEN&_offset=10&_count=10  # Check offset advances

# 3. Chained search
GET /fhir/acme/Observation?patient.name=Baxter&_count=10
GET /fhir/acme/Observation?_page=TOKEN&_offset=10&_count=10  # Check offset advances

# 4. $everything
GET /fhir/acme/Patient/example/$everything?_count=10
GET /fhir/acme/Patient/example/$everything?_page=TOKEN&_offset=10&_count=10  # Check offset advances

# 5. Verify Couchbase document
# - Check that document only has rev 1 (not rev 2, 3, 4)
# - Check that expiration doesn't change
# - Check that currentOffset stays at 0
```

## Summary

The pagination system is now **truly immutable and stateless**:

- Document stores only the list of keys
- Offset is tracked in URL parameters
- No document updates after creation
- TTL honored from initial creation time
- 75% reduction in Couchbase write operations

🎉 **Problem solved!**
