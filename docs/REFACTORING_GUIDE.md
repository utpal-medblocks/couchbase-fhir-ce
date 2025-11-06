# Service Refactoring Guide for CouchbaseGateway

This document shows how to refactor each service to use the centralized `CouchbaseGateway`.

## Pattern 1: N1QL Query Operations

**Before:**

```java
Cluster cluster = connectionService.getConnection("default");
if (cluster == null) {
    throw new RuntimeException("No active connection found");
}
QueryResult result = cluster.query(sql);
```

**After:**

```java
QueryResult result = couchbaseGateway.query("default", sql);
```

## Pattern 2: FTS Search Operations

**Before:**

```java
Cluster cluster = connectionService.getConnection("default");
if (cluster == null) {
    throw new RuntimeException("No active connection found");
}
SearchResult result = cluster.searchQuery(indexName, query, options);
```

**After:**

```java
SearchResult result = couchbaseGateway.searchQuery("default", indexName, query, options);
```

## Pattern 3: Transaction Operations

**Before:**

```java
Cluster cluster = connectionService.getConnection("default");
TransactionContext context = new TransactionContextImpl(cluster, bucketName);
```

**After:**

```java
Cluster cluster = couchbaseGateway.getClusterForTransaction("default");
TransactionContext context = new TransactionContextImpl(cluster, bucketName);
```

**Why `getClusterForTransaction()`?**

- Still enforces circuit breaker
- Returns Cluster for `cluster.transactions()` calls
- Safer than bypassing gateway entirely

## Pattern 4: KV Operations (Collection Access)

**Before:**

```java
Collection collection = connectionService.getCollection("default", bucketName, scope, collectionName);
```

**After:**

```java
// No change needed - connectionService.getCollection() is fine
// It already has connection validation built in
Collection collection = connectionService.getCollection("default", bucketName, scope, collectionName);
```

## Services to Refactor

### ✅ Completed

- [x] **FtsSearchService** - Uses `couchbaseGateway.searchQuery()`
- [x] **SearchService** - Cleaned up connection checks
- [x] **ConditionalPutService** - Cleaned up connection checks
- [x] **PostService** - Uses `couchbaseGateway.query()` for UPSERT

### 🔄 Needs Updates

#### 1. **PutService** (Priority: HIGH)

**File:** `backend/src/main/java/com/couchbase/fhir/resources/service/PutService.java`

**Changes needed:**

```java
// Line 156-162: Update copyExistingResourceToVersions()
// Replace:
String sql = String.format(...);
QueryResult result = cluster.query(sql);

// With:
String sql = String.format(...);
QueryResult result = couchbaseGateway.query("default", sql);
```

#### 2. **DeleteService** (Priority: HIGH)

**File:** `backend/src/main/java/com/couchbase/fhir/resources/service/DeleteService.java`

**Changes needed:**

```java
// Add import:
import com.couchbase.fhir.resources.gateway.CouchbaseGateway;

// Add field:
@Autowired
private CouchbaseGateway couchbaseGateway;

// Update copyCurrentResourceToVersions() - similar to PutService
// Replace cluster.query() with couchbaseGateway.query()
```

#### 3. **FhirBundleProcessingService** (Priority: HIGH - Most Critical)

**File:** `backend/src/main/java/com/couchbase/fhir/resources/service/FhirBundleProcessingService.java`

**Changes needed:**

```java
// Line ~250: Update cluster retrieval
// Replace:
Cluster cluster = connectionService.getConnection("default");
if (cluster == null) {
    throw new RuntimeException("No active connection found");
}

// With:
Cluster cluster = couchbaseGateway.getClusterForTransaction("default");
```

#### 4. **PatchService** (Priority: MEDIUM)

**File:** `backend/src/main/java/com/couchbase/fhir/resources/service/PatchService.java`

**Changes needed:**
Similar to PutService - update any `cluster.query()` calls to `couchbaseGateway.query()`.

#### 5. **BatchKvService** (Priority: LOW)

**File:** `backend/src/main/java/com/couchbase/fhir/resources/service/BatchKvService.java`

**Status:** ✅ Already safe

- Uses `connectionService.getCollection()` which has validation
- No direct cluster.query() calls
- Circuit breaker will still protect via collection access failures

#### 6. **FhirResourceStorageHelper** (If exists)

Check for any direct Cluster usage and apply appropriate pattern.

#### 7. **EverythingService** (If exists)

Check for any direct Cluster usage and apply appropriate pattern.

## Quick Refactoring Script

For services with `cluster.query()` calls:

```bash
# 1. Add import
sed -i '' '/import com.couchbase.client.java.Cluster;/a\
import com.couchbase.fhir.resources.gateway.CouchbaseGateway;
' ServiceName.java

# 2. Add @Autowired field after existing autowires
# (Manual - add near line 30-40):
@Autowired
private CouchbaseGateway couchbaseGateway;

# 3. Replace cluster.query() calls
# Find all instances and replace with couchbaseGateway.query("default", ...)
```

## Testing Checklist

After refactoring each service:

1. **Compile Check**

   ```bash
   cd backend
   mvn compile
   ```

2. **Lint Check**

   - No unused imports
   - No unused fields
   - All methods use gateway

3. **Runtime Test**

   - Start application
   - Test the service endpoint
   - Stop Couchbase
   - Verify:
     - Clean log (single line, no stack trace)
     - HTTP 503 response
     - Circuit breaker opens

4. **Health Check**

   ```bash
   # With DB up
   curl http://localhost:8080/health/readiness
   # Should return 200

   # With DB down
   curl http://localhost:8080/health/readiness
   # Should return 503
   ```

## Complete Example: Refactoring PutService

**Before:**

```java
@Service
public class PutService {
    @Autowired
    private IParser jsonParser;

    @Autowired
    private FhirMetaHelper metaHelper;

    @Autowired
    private CollectionRoutingService collectionRoutingService;

    private int copyExistingResourceToVersions(..., Cluster cluster, ...) {
        String sql = String.format("INSERT INTO...");
        QueryResult result = cluster.query(sql);
        // ...
    }
}
```

**After:**

```java
@Service
public class PutService {
    @Autowired
    private IParser jsonParser;

    @Autowired
    private FhirMetaHelper metaHelper;

    @Autowired
    private CollectionRoutingService collectionRoutingService;

    @Autowired
    private CouchbaseGateway couchbaseGateway;  // ← Added

    private int copyExistingResourceToVersions(..., Cluster cluster, ...) {
        String sql = String.format("INSERT INTO...");
        QueryResult result = couchbaseGateway.query("default", sql);  // ← Changed
        // ...
    }
}
```

## Error Handling

All services automatically get:

- ✅ Circuit breaker protection
- ✅ Clean 503 responses when DB is down
- ✅ Single-line error logs (no stack traces)
- ✅ HAProxy health check integration

No try/catch needed - gateway handles it all!

## Priority Order

1. **FhirBundleProcessingService** (affects all bundle operations)
2. **PutService** (high usage)
3. **DeleteService** (high usage)
4. **PatchService** (medium usage)
5. Other services (as needed)
