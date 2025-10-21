# Centralized Gateway Architecture for Database Access

## Problem

Under load, when Couchbase disconnects due to GC pressure or other issues, the application was:

- Generating massive stack traces in logs
- Trying to execute operations that would fail anyway
- Making debugging difficult with cluttered logs
- Not signaling HAProxy to route traffic elsewhere

## Solution: Gateway Pattern with Circuit Breaker

We've centralized all database access through a single gateway with these benefits:

### 1. **CouchbaseGateway** - Single Point of Entry

All database operations (FTS, N1QL, KV) go through `CouchbaseGateway`:

```java
// Before: Scattered connection checks everywhere
Cluster cluster = connectionService.getConnection("default");
if (cluster == null) {
    throw new RuntimeException("No active connection found");
}
SearchResult result = cluster.searchQuery(...);

// After: Clean gateway call
SearchResult result = couchbaseGateway.searchQuery("default", indexName, query, options);
```

**Location:** `backend/src/main/java/com/couchbase/fhir/resources/gateway/CouchbaseGateway.java`

**Key Features:**

- **Circuit Breaker**: Opens automatically when database is down, fails fast for 30 seconds
- **Clean Fail-Fast**: Single exception type instead of scattered RuntimeExceptions
- **Self-Healing**: Attempts to close circuit after timeout
- **Monitoring**: Exposes circuit state for health checks

### 2. **DatabaseUnavailableException** - Single Exception Type

One predictable exception for all connectivity issues:

```java
throw new DatabaseUnavailableException("No active Couchbase connection: default");
```

**Location:** `backend/src/main/java/com/couchbase/fhir/resources/exceptions/DatabaseUnavailableException.java`

**Benefits:**

- Easy to catch and handle
- Maps cleanly to HTTP 503
- No stack traces cluttering logs

### 3. **DatabaseUnavailableExceptionMapper** - Clean HTTP 503 Mapping

HAPI FHIR interceptor that converts our exception to HTTP 503:

```java
@Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
public boolean handleException(RequestDetails theRequestDetails, Throwable theException) {
    if (theException instanceof DatabaseUnavailableException) {
        logger.error("🔴 Database unavailable - returning 503");  // Clean one-liner
        throw new UnclassifiedServerFailureException(503, "Service temporarily unavailable");
    }
    return true;
}
```

**Location:** `backend/src/main/java/com/couchbase/fhir/resources/interceptor/DatabaseUnavailableExceptionMapper.java`

**Benefits:**

- Single log line instead of huge stack traces
- Proper HTTP 503 status for load balancers
- No more "No active connection" spam in logs

### 4. **Health Endpoints** - HAProxy Integration

New endpoints that reflect actual database state:

```
GET /health/readiness  -> 200 OK (ready) or 503 (not ready)
GET /health/liveness   -> 200 OK (always, unless catastrophic failure)
GET /health            -> Detailed status
```

**Location:** `backend/src/main/java/com/couchbase/admin/health/HealthController.java`

**HAProxy Configuration:**

```haproxy
backend fhir_servers
    option httpchk GET /health/readiness
    http-check expect status 200
    server fhir1 fhir-server:8080 check inter 5s fall 3 rise 2
```

When database is down:

1. `/health/readiness` returns 503
2. HAProxy marks server as down after 3 failures (15 seconds)
3. Traffic routes to healthy instances only
4. When database recovers, circuit closes and server returns to pool

## Changes Made

### Refactored Services

- ✅ **FtsSearchService** - Uses `couchbaseGateway.searchQuery()`
- ✅ **SearchService** - Removed scattered connection checks
- ✅ **ConditionalPutService** - Removed preflight checks

### Services That Need Updates (Examples for You)

Apply the same pattern to:

- **PostService** - `cluster.query()` → `couchbaseGateway.query()`
- **PutService** - Similar refactoring
- **PatchService** - Similar refactoring
- **DeleteService** - Similar refactoring
- **FhirBundleProcessingService** - Use gateway for all operations

**Pattern:**

```java
// Old way
Cluster cluster = connectionService.getConnection("default");
if (cluster == null) {
    throw new RuntimeException("No active connection found");
}
QueryResult result = cluster.query(sql);

// New way
QueryResult result = couchbaseGateway.query("default", sql);
```

## Benefits

### 1. Clean Logs Under Load

**Before:**

```
ERROR - No active connection found
java.lang.RuntimeException: FTS search failed: No active connection found
    at com.couchbase.fhir.resources.service.FtsSearchService.searchForKeys(FtsSearchService.java:121)
    at com.couchbase.fhir.resources.service.SearchService.handleMultipleIncludeSearch(SearchService.java:954)
    ... 150 more lines of stack trace ...
ERROR - Bundle processing failed
... another stack trace ...
```

**After:**

```
ERROR - ⚡ Circuit breaker OPENED - database unavailable
ERROR - 🔴 Database unavailable - returning 503
```

### 2. Better Load Balancer Behavior

- HAProxy automatically removes unhealthy instances
- Traffic goes only to working servers
- Automatic recovery when database comes back

### 3. Reduced Database Load During Recovery

- Circuit breaker prevents overwhelming recovering database
- 30-second cooling period before retry
- Graceful degradation

### 4. Easier Debugging

- Single place to add logging/metrics
- Clear circuit breaker state
- Health endpoint shows exact status

## Monitoring

### Check Circuit Breaker State

```bash
curl http://localhost:8080/health
```

Response when healthy:

```json
{
  "status": "UP",
  "components": {
    "database": { "status": "UP", "type": "Couchbase" },
    "circuitBreaker": { "status": "CLOSED" }
  }
}
```

Response when database down:

```json
{
  "status": "DEGRADED",
  "components": {
    "database": { "status": "DOWN", "type": "Couchbase" },
    "circuitBreaker": { "status": "OPEN" }
  }
}
```

### HAProxy Stats

Check which servers are up/down:

```bash
curl http://localhost:8404/stats
```

## Next Steps

1. **Apply Pattern to Remaining Services**

   - Update PostService, PutService, PatchService, DeleteService
   - Use `couchbaseGateway.query()` or `couchbaseGateway.searchQuery()`
   - Remove old connection checks

2. **Update HAProxy Config**

   - Add health check to backend configuration
   - Set appropriate timeouts (inter 5s, fall 3, rise 2)

3. **Monitor Circuit Breaker**

   - Add metrics export if needed
   - Consider adjusting 30s timeout based on recovery patterns

4. **Test Failure Scenarios**
   - Stop Couchbase and verify logs are clean
   - Verify HAProxy removes instance from pool
   - Verify automatic recovery when database returns

## Files Created/Modified

### New Files

- `backend/src/main/java/com/couchbase/fhir/resources/gateway/CouchbaseGateway.java`
- `backend/src/main/java/com/couchbase/fhir/resources/exceptions/DatabaseUnavailableException.java`
- `backend/src/main/java/com/couchbase/fhir/resources/interceptor/DatabaseUnavailableExceptionMapper.java`
- `backend/src/main/java/com/couchbase/admin/health/HealthController.java`

### Modified Files

- `backend/src/main/java/com/couchbase/admin/connections/service/ConnectionService.java`
  - Added `hasActiveConnection()` for quick checks
- `backend/src/main/java/com/couchbase/fhir/resources/service/FtsSearchService.java`
  - Uses CouchbaseGateway instead of direct Cluster access
- `backend/src/main/java/com/couchbase/fhir/resources/service/SearchService.java`
  - Removed scattered connection checks
  - Kept helper methods for detecting connection errors (for logging)
- `backend/src/main/java/com/couchbase/fhir/resources/service/ConditionalPutService.java`
  - Removed preflight connection check

## Architecture Diagram

```
┌─────────────────┐
│   HAProxy       │ ← Health checks /health/readiness
│  Load Balancer  │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
┌───▼──┐  ┌──▼───┐
│ FHIR │  │ FHIR │
│ Srv1 │  │ Srv2 │
└───┬──┘  └──┬───┘
    │        │
    └───┬────┘
        │
   ┌────▼─────────────┐
   │ CouchbaseGateway │ ← Circuit Breaker
   │   (Single Entry) │
   └────┬─────────────┘
        │
   ┌────▼────────┐
   │  Couchbase  │
   │   Cluster   │
   └─────────────┘
```

When Couchbase fails:

1. CouchbaseGateway detects and opens circuit
2. Returns DatabaseUnavailableException (no stack trace)
3. Mapped to HTTP 503
4. HAProxy marks server down
5. Traffic routes to healthy instances only
