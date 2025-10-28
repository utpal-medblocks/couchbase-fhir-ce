# Pagination Offset Update Fix

## Problem Identified During Testing

### Issue #1: CurrentOffset Not Persisted to Couchbase

**Symptom:**

```json
// Document in Couchbase Admin.cache always shows:
{
  "currentOffset": 10,  // ← Stuck at 10, never advances!
  ...
}

// But URLs show advancing offset:
"url": "...?_page=abc123&_offset=20&_count=10"  // ← offset=20
"url": "...?_page=abc123&_offset=30&_count=10"  // ← offset=30
```

**Root Cause:**

- Code was updating `paginationState.setCurrentOffset()` in memory
- But **never saving it back to Couchbase**
- Each subsequent "next" request fetched the stale state from Couchbase
- Result: All pages after the first returned the same data

**Code Before:**

```java
// SearchService.java - handleContinuationTokenNewPagination()
paginationState.setCurrentOffset(paginationState.getCurrentOffset() + currentPageKeys.size());

// Check if last page
if (isLastPage) {
    searchStateManager.removePaginationState(continuationToken, bucketName);
}
// ❌ BUG: If not last page, updated offset is NOT saved back to Couchbase!
```

**Fix Applied:**

```java
// Update offset in memory
paginationState.setCurrentOffset(paginationState.getCurrentOffset() + currentPageKeys.size());

// Check if last page
if (isLastPage) {
    searchStateManager.removePaginationState(continuationToken, bucketName);
} else {
    // ✅ FIX: Save updated offset back to Couchbase
    searchStateManager.updatePaginationState(continuationToken, bucketName, paginationState);
    logger.debug("🔑 Updated pagination state offset to {}", paginationState.getCurrentOffset());
}
```

### Issue #2: Configuration File Confusion

**Question:** Why do we need `application-revinclude.properties` if Couchbase handles TTL?

**Answer:**

- The property `fhir.search.state.ttl.minutes=3` is **still needed**
- It's read by `PaginationCacheService` when storing documents
- It tells Couchbase: "expire this document after 3 minutes"
- Used in: `UpsertOptions.upsertOptions().expiry(Duration.ofMinutes(paginationTtlMinutes))`

**Updated Configuration:**

```properties
# Pagination State Configuration
# ================================
# Pagination states are now stored OFF-HEAP in Couchbase Admin.cache collection
# to prevent heap exhaustion (previously consumed 171MB / 24.77% of heap)

# Time to live for pagination states in minutes (default: 3 minutes)
# This value is passed to Couchbase when storing pagination states
# Couchbase automatically expires documents after this TTL - no manual cleanup needed
# Reduced from 15 to 3 minutes to prevent state accumulation (1600 → 320 concurrent states)
fhir.search.state.ttl.minutes=3

# Cleanup interval for legacy search states in minutes (default: 5 minutes)
# Only applies to legacy in-memory SearchState cache (rarely used)
# New pagination states are cleaned up automatically by Couchbase TTL
fhir.search.state.cleanup.interval.minutes=5
```

## Changes Made

### 1. SearchStateManager.java - NEW METHOD

**Added:**

```java
/**
 * Update pagination state in Couchbase (e.g., after advancing offset).
 *
 * This is needed because pagination state is mutable - we advance the offset
 * after each page request. We must persist the updated state back to Couchbase
 * so the next pagination request gets the correct offset.
 *
 * @param token The pagination token
 * @param bucketName The FHIR bucket name
 * @param paginationState The updated pagination state
 */
public void updatePaginationState(String token, String bucketName, PaginationState paginationState) {
    if (token != null && bucketName != null && paginationState != null) {
        // Re-store with same token (upsert will update existing document)
        paginationCacheService.storePaginationState(bucketName, token, paginationState);
        logger.debug("Updated pagination state: token={}, bucket={}, offset={}",
                    token, bucketName, paginationState.getCurrentOffset());
    }
}
```

**How It Works:**

- Calls `storePaginationState()` again with the same token
- Couchbase upsert updates the existing document
- TTL is refreshed (good - keeps state alive while actively used)

### 2. SearchService.java - Updated Pagination Handler

**Location:** `handleContinuationTokenNewPagination()`

**Before:**

```java
paginationState.setCurrentOffset(paginationState.getCurrentOffset() + currentPageKeys.size());

if (isLastPage) {
    searchStateManager.removePaginationState(continuationToken, bucketName);
}
// ❌ Updated offset lost!
```

**After:**

```java
paginationState.setCurrentOffset(paginationState.getCurrentOffset() + currentPageKeys.size());

if (isLastPage) {
    searchStateManager.removePaginationState(continuationToken, bucketName);
} else {
    // ✅ Save updated offset back to Couchbase
    searchStateManager.updatePaginationState(continuationToken, bucketName, paginationState);
    logger.debug("🔑 Updated pagination state offset to {}", paginationState.getCurrentOffset());
}
```

### 3. FhirCouchbaseResourceProvider.java - Updated $everything Handler

**Location:** `buildEverythingContinuationBundle()`

**Before:**

```java
if (paginationState != null) {
    paginationState.setCurrentOffset(paginationState.getCurrentOffset() + pageResources.size());
}
// ❌ Updated offset lost!
```

**After:**

```java
if (paginationState != null) {
    paginationState.setCurrentOffset(paginationState.getCurrentOffset() + pageResources.size());
    // ✅ Save updated offset back to Couchbase
    searchStateManager.updatePaginationState(continuationToken, bucketName, paginationState);
    logger.debug("🔑 Updated $everything pagination state offset to {}", paginationState.getCurrentOffset());
}
```

### 4. application-revinclude.properties - Clarified Comments

**Updated comments to explain:**

- Why TTL property is still needed (passed to Couchbase)
- That Couchbase handles automatic cleanup
- That cleanup interval only applies to legacy cache

## Testing Results - Expected Behavior

### Initial Search Request

```http
GET /fhir/acme/Patient?_count=10&_revinclude=Patient:*
```

**Response:**

```json
{
  "resourceType": "Bundle",
  "entry": [
    /* 10 resources */
  ],
  "link": [
    {
      "relation": "next",
      "url": "http://localhost:8080/fhir/acme/Patient?_page=abc123&_offset=10&_count=10"
    }
  ]
}
```

**Couchbase Document (Admin.cache):**

```json
{
  "currentOffset": 10,  // ← Advanced to 10 after first page
  "allDocumentKeys": ["Patient/1", "Patient/2", ..., "Patient/48"],
  "pageSize": 10,
  "searchType": "revinclude",
  "expiresAt": "2025-10-23T12:54:41.184351"
}
```

### Second Page Request

```http
GET /fhir/acme/Patient?_page=abc123&_offset=10&_count=10
```

**What Happens:**

1. ✅ Retrieve state from Couchbase → `currentOffset=10`
2. ✅ Call `getNextPageKeys()` → returns keys[10:20]
3. ✅ Fetch 10 resources via KV
4. ✅ Update offset: `currentOffset = 10 + 10 = 20`
5. ✅ **Save updated state back to Couchbase** ← NEW!
6. ✅ Return bundle with next URL `_offset=20`

**Couchbase Document (After Update):**

```json
{
  "currentOffset": 20,  // ← NOW UPDATED! ✅
  "allDocumentKeys": ["Patient/1", "Patient/2", ..., "Patient/48"],
  "pageSize": 10,
  "searchType": "revinclude",
  "expiresAt": "2025-10-23T12:57:41.184351"  // ← TTL refreshed
}
```

### Third Page Request

```http
GET /fhir/acme/Patient?_page=abc123&_offset=20&_count=10
```

**What Happens:**

1. ✅ Retrieve state from Couchbase → `currentOffset=20` (correct!)
2. ✅ Call `getNextPageKeys()` → returns keys[20:30]
3. ✅ Fetch 10 resources via KV
4. ✅ Update offset: `currentOffset = 20 + 10 = 30`
5. ✅ Save updated state back to Couchbase
6. ✅ Return bundle with next URL `_offset=30`

### After 3 Minutes (TTL Expired)

```http
GET /fhir/acme/Patient?_page=abc123&_offset=30&_count=10
```

**Response:**

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "processing",
      "diagnostics": "Pagination state has expired or is invalid. Please repeat your original search."
    }
  ]
}
```

**HTTP Status:** 410 Gone (FHIR compliant)

## Performance Considerations

### Additional Couchbase Writes

**Before Fix:**

- 1 write per initial search (store pagination state)
- 0 writes per "next" page
- 1 write on last page (optional cleanup)

**After Fix:**

- 1 write per initial search (store pagination state)
- **1 write per "next" page** (update offset) ← NEW
- 1 write on last page (optional cleanup)

**Impact:**

- For a search with 5 pages: 1 initial write → 1 + 4 writes = 5 writes total
- Each write is a KV upsert (~1ms latency)
- Trade-off: Correctness > minimal latency
- **Alternative:** Could batch updates or redesign as stateless (more complex)

### TTL Refresh Side Effect

**Benefit:**

- Each update refreshes the TTL
- If user is actively paginating, state stays alive
- Only expires after 3 minutes of inactivity
- Better UX for slow but active pagination

**Example:**

```
T=0:00  - Initial search → expires at T=3:00
T=0:30  - Page 2 → expires at T=3:30 (refreshed)
T=1:00  - Page 3 → expires at T=4:00 (refreshed)
T=1:30  - Page 4 → expires at T=4:30 (refreshed)
```

User has 3 minutes of inactivity tolerance, not 3 minutes total.

## Verification Steps

### 1. Test Offset Progression

```bash
# Initial search
curl "http://localhost:8080/fhir/acme/Patient?_count=10&_revinclude=*"
# Note the _page token

# Check Couchbase document
# Should show: "currentOffset": 10

# Page 2
curl "http://localhost:8080/fhir/acme/Patient?_page=TOKEN&_offset=10&_count=10"

# Check Couchbase document again
# Should show: "currentOffset": 20  ← FIXED!

# Page 3
curl "http://localhost:8080/fhir/acme/Patient?_page=TOKEN&_offset=20&_count=10"

# Check Couchbase document again
# Should show: "currentOffset": 30  ← FIXED!
```

### 2. Verify Different Resources

```bash
# Make sure each page returns different resources
# Before fix: Pages 2, 3, 4 would all return same 10 resources
# After fix: Each page returns next 10 resources
```

### 3. Test TTL Expiry

```bash
# Initial search
curl "http://localhost:8080/fhir/acme/Patient?_count=10&_revinclude=*"

# Wait 3+ minutes
sleep 200

# Try to get next page - should return 410 Gone
curl "http://localhost:8080/fhir/acme/Patient?_page=TOKEN&_offset=10&_count=10"
# Expected: HTTP 410 with OperationOutcome
```

### 4. Test Active Pagination TTL Refresh

```bash
# Initial search
curl "http://localhost:8080/fhir/acme/Patient?_count=10&_revinclude=*"

# Page through every 2 minutes (within TTL)
sleep 120 && curl "...?_page=TOKEN&_offset=10&_count=10"
sleep 120 && curl "...?_page=TOKEN&_offset=20&_count=10"
sleep 120 && curl "...?_page=TOKEN&_offset=30&_count=10"

# Total elapsed: 6 minutes, but should still work because TTL keeps refreshing
```

## Migration Notes

### Existing Pagination Tokens

**Before Fix Deployed:**

- Some pagination tokens may exist with stale offsets

**After Fix Deployed:**

- Old tokens will continue to work
- Offset will start advancing correctly from first "next" request after deployment
- No migration needed - states will naturally expire within 3 minutes

### Backward Compatibility

- ✅ All changes are backward compatible
- ✅ No API changes
- ✅ No database schema changes
- ✅ Existing pagination tokens work correctly
- ✅ Can rollback if needed

## Summary

### Root Cause

Pagination state offset was updated in memory but never persisted back to Couchbase, causing all subsequent pages to return the same data.

### Fix

Added `updatePaginationState()` method to persist offset updates after each page request.

### Files Changed

1. `SearchStateManager.java` - Added updatePaginationState() method
2. `SearchService.java` - Call updatePaginationState() after advancing offset
3. `FhirCouchbaseResourceProvider.java` - Call updatePaginationState() for $everything
4. `application-revinclude.properties` - Clarified configuration comments

### Side Effects

- +1 Couchbase write per pagination request (~1ms overhead)
- TTL refreshes on each access (better UX for active pagination)

### Testing Required

- ✅ Verify offset advances correctly in Couchbase documents
- ✅ Verify each page returns different resources
- ✅ Verify 410 Gone after TTL expiry
- ✅ Load test to ensure no performance degradation

**Status:** ✅ Fix Complete, Ready for Testing
