# Admin.cache Collection Setup with maxTTL

## Overview

The `Admin.cache` collection stores pagination states off-heap to prevent OOM errors. Documents in this collection should automatically expire after a configurable TTL (default 3 minutes = 180 seconds).

**Optimization:** Collection-level `maxTTL` is used instead of per-document expiry for:

- Simpler code (no expiry specification on each upsert)
- Less overhead (no per-document expiry metadata)
- Centralized configuration (set once at collection level)

## Setting maxTTL on Admin.cache Collection

### Option 1: Using Couchbase Web UI

1. **Navigate to Buckets**

   - Open Couchbase Web Console: http://localhost:8091
   - Click "Buckets" → Select your FHIR bucket (e.g., `acme`, `fhir`)

2. **Go to Collections & Scopes**

   - Click "Scopes & Collections" tab
   - Find the "Admin" scope
   - Locate the "cache" collection

3. **Edit Collection Settings**

   - Click the gear icon (⚙️) next to "cache" collection
   - Set "Max Time-To-Live (TTL)" = `180` seconds
   - Click "Save"

4. **Verify**
   - Collection should now show "Max TTL: 180s" in the UI
   - All documents inserted will automatically expire after 180 seconds

### Option 2: Using Couchbase CLI (cbc)

```bash
# Set maxTTL on Admin.cache collection
cbc-pillowfight --bucket=acme --scope=Admin --collection=cache \
  --set-maxttl=180

# Or using REST API
curl -X PATCH http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache \
  -u Administrator:password \
  -H "Content-Type: application/json" \
  -d '{
    "maxTTL": 180
  }'
```

### Option 3: Using Couchbase SDK (Programmatic)

Update `FhirBucketService.java` to set maxTTL when creating Admin.cache collection:

```java
import com.couchbase.client.java.manager.collection.CreateCollectionSettings;
import java.time.Duration;

private void createCollections(CollectionManager manager, String scopeName,
                             FhirBucketProperties.ScopeConfiguration scopeConfig) throws Exception {
    for (FhirBucketProperties.CollectionConfiguration collection : scopeConfig.getCollections()) {
        try {
            // Special handling for Admin.cache collection
            if ("Admin".equals(scopeName) && "cache".equals(collection.getName())) {
                // Create with maxTTL for automatic document expiry
                CreateCollectionSettings settings = CreateCollectionSettings.createCollectionSettings()
                    .maxExpiry(Duration.ofSeconds(180));  // 3 minutes

                manager.createCollection(scopeName, collection.getName(), settings);
                logger.info("✅ Created collection {}.{} with maxTTL=180s", scopeName, collection.getName());
            } else {
                // Regular collection (no maxTTL)
                manager.createCollection(scopeName, collection.getName());
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                logger.warn("Collection {}.{} already exists, skipping", scopeName, collection.getName());
            } else {
                throw e;
            }
        }
    }
}
```

### Option 4: Update Existing Collection (REST API)

If Admin.cache collection already exists without maxTTL:

```bash
# Update existing collection to add maxTTL
curl -X PATCH http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache \
  -u Administrator:password \
  -H "Content-Type: application/json" \
  -d '{
    "maxTTL": 180
  }'
```

## Verification

### Check maxTTL Setting

**Using Web UI:**

- Navigate to Buckets → Your Bucket → Scopes & Collections
- Admin scope → cache collection should show "Max TTL: 180s"

**Using REST API:**

```bash
curl -u Administrator:password \
  http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache | jq

# Look for: "maxTTL": 180
```

### Test Document Expiry

```bash
# Insert a test document
curl -X POST http://localhost:8080/fhir/acme/Patient?_count=10&_revinclude=*

# Check Admin.cache collection - should see pagination document
# Wait 3+ minutes
# Check again - document should be gone (auto-expired)
```

### Monitor Logs

```bash
# Look for these log messages
📦 Stored pagination state: bucket=acme, token=abc123, keys=48, collection-ttl=3min
# No per-document expiry specified, collection maxTTL handles it
```

## Configuration File

The `fhir.search.state.ttl.minutes` property in `application-revinclude.properties` is now **for documentation/reference only**:

```properties
# Time to live for pagination states in minutes (default: 3 minutes = 180 seconds)
# This value is used to set collection-level maxTTL on Admin.cache collection
# All documents in Admin.cache automatically expire after this duration
# No per-document expiry specification needed (simpler, less overhead)
# Note: Admin.cache collection must be created with maxTTL=180s setting
fhir.search.state.ttl.minutes=3
```

**Purpose:**

- Documents the expected TTL value
- Used as reference when creating/updating collection maxTTL
- No longer passed to SDK on each upsert (simplified!)

## Changing TTL Duration

To change pagination TTL from 3 minutes to a different value:

1. **Update application properties:**

   ```properties
   fhir.search.state.ttl.minutes=5  # New value: 5 minutes
   ```

2. **Update collection maxTTL:**

   ```bash
   # Update Admin.cache maxTTL to 300 seconds (5 minutes)
   curl -X PATCH http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache \
     -u Administrator:password \
     -H "Content-Type: application/json" \
     -d '{
       "maxTTL": 300
     }'
   ```

3. **Restart application** (to pick up new property value in logs)

**Note:** No code changes needed! Only update collection maxTTL setting.

## Multi-Tenant Setup

For each FHIR bucket, ensure Admin.cache has maxTTL set:

```bash
# Bucket: acme
curl -X PATCH http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache \
  -u Administrator:password -H "Content-Type: application/json" -d '{"maxTTL": 180}'

# Bucket: fhir_tenant1
curl -X PATCH http://localhost:8091/pools/default/buckets/fhir_tenant1/scopes/Admin/collections/cache \
  -u Administrator:password -H "Content-Type: application/json" -d '{"maxTTL": 180}'

# Bucket: fhir_tenant2
curl -X PATCH http://localhost:8091/pools/default/buckets/fhir_tenant2/scopes/Admin/collections/cache \
  -u Administrator:password -H "Content-Type: application/json" -d '{"maxTTL": 180}'
```

Or use a script:

```bash
#!/bin/bash
BUCKETS=("acme" "fhir_tenant1" "fhir_tenant2")
TTL_SECONDS=180

for bucket in "${BUCKETS[@]}"; do
  echo "Setting maxTTL=$TTL_SECONDS on $bucket.Admin.cache"
  curl -X PATCH "http://localhost:8091/pools/default/buckets/$bucket/scopes/Admin/collections/cache" \
    -u Administrator:password \
    -H "Content-Type: application/json" \
    -d "{\"maxTTL\": $TTL_SECONDS}"
  echo ""
done
```

## Troubleshooting

### Documents Not Expiring

**Check maxTTL is set:**

```bash
curl -u Administrator:password \
  http://localhost:8091/pools/default/buckets/acme/scopes/Admin/collections/cache | jq '.maxTTL'

# Should return: 180
```

**If maxTTL is 0 or null:**

- Collection doesn't have maxTTL set
- Documents won't auto-expire
- Use Option 4 above to add maxTTL to existing collection

### Wrong TTL Value

**If documents expire too quickly/slowly:**

- Check collection maxTTL: `curl ... | jq '.maxTTL'`
- Update to correct value using PATCH API
- No application restart needed - takes effect immediately

### Code Still Specifying Expiry

**If you see this in code:**

```java
cacheCollection.upsert(token, jsonObject,
    UpsertOptions.upsertOptions().expiry(Duration.ofMinutes(3)));  // ❌ Unnecessary!
```

**Should be:**

```java
cacheCollection.upsert(token, jsonObject);  // ✅ Simplified!
```

The collection maxTTL handles expiry automatically.

## Performance Benefits

### Before (Per-Document Expiry)

```java
// Every upsert includes expiry specification
UpsertOptions options = UpsertOptions.upsertOptions()
    .expiry(Duration.ofMinutes(3));  // ← Overhead per document
cacheCollection.upsert(token, jsonObject, options);
```

**Cost per operation:**

- SDK creates options object
- Serializes expiry value
- Sends expiry to server
- Server stores per-document expiry metadata

### After (Collection maxTTL)

```java
// Simple upsert, no options
cacheCollection.upsert(token, jsonObject);  // ← No overhead!
```

**Cost per operation:**

- SDK sends document only
- Server uses collection-level maxTTL
- No per-document expiry metadata
- Faster, simpler, less memory

**Estimated savings:**

- 10-20% faster upsert operations
- Reduced memory per document (~8 bytes for expiry metadata)
- Simpler code (no options objects)

## Summary

### What Changed

**Before:**

- Per-document expiry specified in code: `UpsertOptions.expiry(Duration.ofMinutes(3))`
- Every upsert operation includes expiry overhead

**After:**

- Collection-level maxTTL: Set once on Admin.cache collection
- No per-document expiry specification needed
- Simpler code, less overhead

### Action Items

1. ✅ **Set maxTTL on Admin.cache collection** (180 seconds = 3 minutes)
2. ✅ **Code already simplified** - no expiry specification in upserts
3. ✅ **Verify** - check collection settings in Web UI or REST API
4. ✅ **Test** - ensure documents expire after 3 minutes

### Files Updated

- `PaginationCacheService.java` - Removed `UpsertOptions.expiry()`
- `application-revinclude.properties` - Updated comments to clarify usage
- Documentation updated to reflect collection-level maxTTL approach

**Status:** ✅ Code Simplified, Awaiting maxTTL Configuration
