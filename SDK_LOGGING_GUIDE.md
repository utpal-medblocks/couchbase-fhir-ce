# Couchbase SDK Logging Guide

## Log Files

- **Application logs**: `/app/logs/fhir.log` (your FHIR server logs)
- **SDK logs**: `/app/logs/sdk.log` (Couchbase SDK internal operations)

Both are mounted to `./logs/` on your host machine.

---

## What Each SDK Logger Shows

### 1. `com.couchbase.core` (Currently: INFO)
**What it logs:**
- KV operation lifecycle (get, upsert, insert, remove)
- Request timeouts and retries
- Circuit breaker events
- Operation scheduling and dispatch

**INFO shows:**
```
Request dispatched: GetRequest{key="Patient/123"}
Request completed: 2ms
Timeout occurred: GetRequest, attempt 3/14
```

**DEBUG shows (VERY VERBOSE):**
- Every request parameter
- Every retry reason
- Queue depths
- Internal state transitions

**When to use:**
- INFO: Find slow operations, timeouts, retry storms
- DEBUG: Deep dive into why specific requests are slow

---

### 2. `com.couchbase.client` (Currently: INFO)
**What it logs:**
- High-level client operations
- Collection map refreshes (your warmup issue!)
- Query/FTS execution
- Bucket open/close events

**INFO shows:**
```
Collection map refresh started for bucket: fhir
Collection map refresh completed: 150ms
FTS query executed: 45ms
```

**When to use:**
- INFO: Find collection map contention, query timing
- DEBUG: See query DSL and response parsing

---

### 3. `com.couchbase.io` (Currently: WARN)
**What it logs:**
- Low-level network I/O
- Socket connect/disconnect
- Data sent/received on wire
- Connection pooling

**WARN shows:**
- Connection failures
- Network errors

**INFO shows:**
- Every connection established
- Keep-alive pings

**DEBUG shows (EXTREMELY VERBOSE):**
- Every byte sent/received
- Protocol-level details

**When to use:**
- WARN: Production (errors only)
- INFO: Diagnose connection pool issues
- DEBUG: Network troubleshooting (massive logs!)

---

### 4. `com.couchbase.endpoint` / `com.couchbase.node` (Currently: WARN)
**What it logs:**
- Cluster topology changes
- Node failures/recovery
- Endpoint lifecycle (connect/disconnect)
- Service availability (KV, Query, FTS)

**WARN shows:**
```
Node ec2-54-69-205-199.us-west-2.compute.amazonaws.com became unavailable
Endpoint disconnected: KV service
```

**When to use:**
- WARN: Production (cluster health issues)
- INFO: Understand cluster topology during tests

---

### 5. `com.couchbase.transactions` (Currently: INFO)
**What it logs:**
- Transaction lifecycle (begin, commit, rollback)
- ATR (Active Transaction Record) operations
- Conflict detection and resolution
- Staged mutations

**INFO shows:**
```
Transaction started: txn-abc123
Transaction committed: 45ms, 3 mutations
Transaction rolled back: Conflict on Patient/123
```

**DEBUG shows (VERY VERBOSE):**
- Every staged write
- Every ATR update
- Retry logic details

**When to use:**
- INFO: Transaction timing and conflict rates
- DEBUG: Diagnose specific transaction failures

**⚠️ WARNING:** Transactions at DEBUG level creates MASSIVE logs (10-100x more data)

---

## Recommended Settings for Performance Testing

### Phase 1: Initial Diagnosis (Current Settings)
```xml
<logger name="com.couchbase.core" level="INFO"/>          <!-- Operation timing -->
<logger name="com.couchbase.client" level="INFO"/>        <!-- Collection maps -->
<logger name="com.couchbase.transactions" level="INFO"/>  <!-- Transaction perf -->
<logger name="com.couchbase.io" level="WARN"/>            <!-- Errors only -->
<logger name="com.couchbase.endpoint" level="WARN"/>      <!-- Errors only -->
```

**What you'll see:**
- Operation timings
- Timeout/retry events
- Collection map refresh contention
- Transaction commit times

**Log size:** Moderate (~10-50MB for typical load test)

---

### Phase 2: Deep Dive (If Needed)
If you find issues, enable DEBUG for specific area:

```xml
<!-- If seeing timeouts/retries -->
<logger name="com.couchbase.core" level="DEBUG"/>

<!-- If seeing collection map issues -->
<logger name="com.couchbase.client" level="DEBUG"/>

<!-- If seeing transaction conflicts -->
<logger name="com.couchbase.transactions" level="DEBUG"/>
```

**⚠️ WARNING:** DEBUG creates 10-100x more logs! Use sparingly.

---

### Phase 3: Production
```xml
<logger name="com.couchbase" level="WARN"/>
```

Only errors and warnings logged.

---

## What to Look For in SDK Logs

### 1. **Timeouts**
```
WARN Timeout occurred: GetRequest{key="Patient/123"}, attempt 3/14
```
**Indicates:** Network latency or Couchbase cluster overload

---

### 2. **Retries**
```
INFO Request retried: reason=COLLECTION_MAP_REFRESH_IN_PROGRESS, attempt=5
```
**Indicates:** Collection map contention (your warmup issue!)

---

### 3. **Slow Operations**
```
INFO GetRequest completed: 1250ms (key=Patient/123)
```
**Indicates:** Specific document or operation is slow

---

### 4. **Circuit Breaker**
```
WARN Circuit breaker opened for endpoint: 10.0.0.132:11210
```
**Indicates:** Too many failures, SDK is throttling requests

---

### 5. **Transaction Conflicts**
```
INFO Transaction rolled back: Conflict on Patient/123
```
**Indicates:** High contention on specific documents

---

## Quick Analysis Commands

```bash
# Count timeouts
grep "Timeout occurred" logs/sdk.log | wc -l

# Count retries by reason
grep "Request retried" logs/sdk.log | grep -oP "reason=\K[A-Z_]+" | sort | uniq -c

# Find slowest operations
grep "completed:" logs/sdk.log | grep -oP "\d+ms" | sort -n | tail -20

# Find collection map refresh times
grep "Collection map refresh" logs/sdk.log

# Transaction commit times
grep "Transaction committed" logs/sdk.log | grep -oP "\d+ms"
```

---

## Current Configuration

Your SDK logs are now configured to show:
- ✅ Operation timing (core + client at INFO)
- ✅ Transaction performance (transactions at INFO)
- ✅ Network/cluster errors only (io/endpoint at WARN)
- ✅ Written to both `/app/logs/sdk.log` AND console
- ✅ Daily rotation, 7 days retention, 1GB cap

**Next steps:**
1. Restart your Docker container
2. Run load test
3. Check `./logs/sdk.log` for performance insights

**If you need more detail:** Change specific logger to DEBUG in `logback-spring.xml`

