# Testing Guide: Gateway Architecture & Circuit Breaker

This guide walks you through testing the new gateway architecture to verify clean failure handling and HAProxy integration.

## Prerequisites

- Docker Compose environment running
- HAProxy configured with health checks (see `haproxy.cfg`)
- FHIR server with CouchbaseGateway integrated

## Test Scenarios

### Scenario 1: Normal Operation (Everything Working)

**Goal:** Verify system works normally when database is healthy.

```bash
# 1. Ensure everything is running
docker-compose ps

# 2. Check health endpoints
curl http://localhost:8080/health/readiness
# Expected: 200 OK
# {
#   "status": "READY",
#   "database": "UP",
#   "circuitBreaker": "CLOSED",
#   "timestamp": 1234567890
# }

curl http://localhost:8080/health
# Expected: 200 OK with detailed status

# 3. Check HAProxy stats
curl http://admin:admin@localhost/haproxy?stats
# Look for backend server status - should be "UP"

# 4. Make a FHIR request
curl http://localhost/fhir/test/Patient
# Expected: Normal FHIR response

# 5. Check logs - should be normal operation logs
docker-compose logs fhir-server | tail -50
```

**Expected Results:**

- ✅ All health checks return 200
- ✅ HAProxy shows server as UP
- ✅ FHIR requests work normally
- ✅ Logs show normal operations

---

### Scenario 2: Database Failure (Graceful Degradation)

**Goal:** Verify clean failure handling when database goes down.

```bash
# 1. Baseline check - everything working
curl http://localhost/fhir/test/Patient
# Expected: 200 OK

# 2. Stop Couchbase to simulate DB failure
docker-compose stop couchbase
# Or: docker stop <couchbase-container-id>

# 3. Wait a moment for circuit breaker to detect failure
sleep 2

# 4. Make a FHIR request
curl -v http://localhost/fhir/test/Patient
# Expected: HTTP 503 Service Unavailable

# 5. Check health endpoint
curl http://localhost:8080/health/readiness
# Expected: 503 Service Unavailable
# {
#   "status": "NOT_READY",
#   "database": "DOWN",
#   "circuitBreaker": "OPEN",
#   "timestamp": 1234567890
# }

# 6. Check logs for clean error messages
docker-compose logs fhir-server | tail -30
```

**Expected Results:**

- ✅ FHIR requests return 503 (not 500)
- ✅ Health check returns 503
- ✅ Circuit breaker shows OPEN
- ✅ Logs show SINGLE LINE: "⚡ Circuit breaker OPENED - database unavailable"
- ✅ Logs show: "🔴 Database unavailable - returning 503"
- ❌ NO massive stack traces in logs

**Wait 15 seconds, then check HAProxy:**

```bash
# After 3 failed health checks (5s * 3 = 15s)
curl http://admin:admin@localhost/haproxy?stats
# Look for backend server - should show "DOWN" with red indicator
```

**Expected Results:**

- ✅ HAProxy marks server as DOWN after 15 seconds
- ✅ Traffic stops routing to failed instance

---

### Scenario 3: Database Recovery (Self-Healing)

**Goal:** Verify automatic recovery when database comes back.

```bash
# 1. Start Couchbase again
docker-compose start couchbase

# 2. Wait for Couchbase to be ready
sleep 10

# 3. Wait for circuit breaker timeout (30 seconds from failure)
# The circuit will try to close after 30 seconds

# 4. Make a FHIR request to trigger circuit check
curl http://localhost/fhir/test/Patient
# Expected: 200 OK (may take 1-2 tries as circuit closes)

# 5. Check health endpoint
curl http://localhost:8080/health/readiness
# Expected: 200 OK
# {
#   "status": "READY",
#   "database": "UP",
#   "circuitBreaker": "CLOSED"
# }

# 6. Check logs for recovery
docker-compose logs fhir-server | tail -20
```

**Expected Results:**

- ✅ Logs show: "✅ Circuit breaker CLOSED - database recovered"
- ✅ FHIR requests work again
- ✅ Health check returns 200

**Wait 10 seconds, then check HAProxy:**

```bash
# After 2 successful health checks (5s * 2 = 10s)
curl http://admin:admin@localhost/haproxy?stats
# Look for backend server - should show "UP" with green indicator
```

**Expected Results:**

- ✅ HAProxy marks server as UP after 10 seconds
- ✅ Traffic resumes to recovered instance

---

### Scenario 4: Load Test Under Failure

**Goal:** Verify clean logs and behavior under load when DB fails.

```bash
# 1. Start load test
cd load-tests
locust -f locustfile.py --host=http://localhost --users=50 --spawn-rate=10

# 2. Let it run for 30 seconds with DB up
# Check stats, should be normal

# 3. Stop Couchbase while load test is running
docker-compose stop couchbase

# 4. Observe behavior for 30 seconds
# - Watch HAProxy stats
# - Watch FHIR server logs
# - Watch Locust failure rate

# 5. Check logs
docker-compose logs fhir-server | tail -100
```

**Expected Results:**

- ✅ Logs remain CLEAN (no 150-line stack traces)
- ✅ Single line per error: "🔴 Database unavailable - returning 503"
- ✅ Circuit breaker opens quickly
- ✅ All requests fail with 503 (not timeouts)
- ✅ HAProxy removes instance within 15 seconds
- ✅ No memory leaks from error object accumulation

**Before vs After Comparison:**

| Metric                  | Before Gateway        | After Gateway      |
| ----------------------- | --------------------- | ------------------ |
| Log size during failure | 10MB+ stack traces    | 100KB clean logs   |
| Error response time     | 30s timeout           | Instant 503        |
| HAProxy behavior        | Routes to dead server | Removes within 15s |
| Recovery                | Manual restart needed | Automatic          |

---

### Scenario 5: Multi-Instance Test (If you have multiple backends)

**Goal:** Verify HAProxy routes around failed instances.

```bash
# 1. Scale up FHIR servers
docker-compose up -d --scale fhir-server=3

# 2. Check all are healthy
for i in {8080..8082}; do
  curl http://localhost:$i/health/readiness
done

# 3. Stop Couchbase
docker-compose stop couchbase

# 4. Observe HAProxy routing
watch -n 1 'curl -s http://admin:admin@localhost/haproxy?stats | grep fhir-server'

# 5. Make FHIR requests
for i in {1..10}; do
  curl -w "\nStatus: %{http_code}\n" http://localhost/fhir/test/Patient
  sleep 1
done
```

**Expected Results:**

- ✅ All instances return 503
- ✅ HAProxy marks all as DOWN
- ✅ Requests get consistent 503 (no timeouts)

**Restart one instance's Couchbase:**

```bash
# Restart DB
docker-compose start couchbase

# Wait and observe
watch -n 1 'curl -s http://localhost:8080/health/readiness | jq .'
```

**Expected Results:**

- ✅ Recovered instances come back UP in HAProxy
- ✅ Traffic routes to healthy instances only

---

## Key Metrics to Monitor

### 1. Log Cleanliness

```bash
# Count error lines before gateway (should be thousands)
docker-compose logs fhir-server | grep "ERROR" | wc -l

# Count after gateway (should be < 100 even under heavy failure)
docker-compose logs fhir-server | grep "ERROR" | wc -l
```

### 2. Circuit Breaker State

```bash
# Check circuit breaker status
curl http://localhost:8080/health | jq '.components.circuitBreaker.status'
# Should be: "CLOSED" (normal) or "OPEN" (failing)
```

### 3. HAProxy Server Status

```bash
# Check server status programmatically
curl -s http://admin:admin@localhost/haproxy?stats\;csv | grep fhir-server | cut -d, -f18
# Should be: "UP" or "DOWN"
```

### 4. Response Times

```bash
# Time requests during failure
time curl http://localhost/fhir/test/Patient

# Before gateway: 30+ seconds (timeout)
# After gateway: < 1 second (immediate 503)
```

---

## Troubleshooting

### Issue: Health check always returns 503

**Cause:** Database never connected or circuit stuck open.

**Fix:**

```bash
# Check Couchbase is running
docker-compose ps couchbase

# Check connection in logs
docker-compose logs fhir-server | grep "Connection"

# Restart FHIR server to reset circuit
docker-compose restart fhir-server
```

### Issue: HAProxy not removing failed server

**Cause:** Health check configuration issue.

**Fix:**

```bash
# Check HAProxy config
cat haproxy.cfg | grep -A 5 "option httpchk"

# Should see:
# option httpchk GET /health/readiness
# http-check expect status 200

# Reload HAProxy
docker-compose restart haproxy
```

### Issue: Still seeing stack traces

**Cause:** Some service not using gateway.

**Fix:**

```bash
# Find which service
docker-compose logs fhir-server | grep -B 5 "No active connection found"

# Check service uses gateway
grep "couchbaseGateway" backend/src/main/java/com/couchbase/fhir/resources/service/[ServiceName].java
```

---

## Success Criteria Checklist

After testing, verify:

- [ ] ✅ Health check returns 200 when DB is up
- [ ] ✅ Health check returns 503 when DB is down
- [ ] ✅ Circuit breaker opens on DB failure
- [ ] ✅ Circuit breaker closes on DB recovery
- [ ] ✅ HAProxy removes failed instances (15s)
- [ ] ✅ HAProxy adds recovered instances (10s)
- [ ] ✅ Logs show single-line errors (no stack traces)
- [ ] ✅ FHIR requests return 503 instantly (not timeout)
- [ ] ✅ Load test with failure produces clean logs
- [ ] ✅ Automatic recovery after DB restart
- [ ] ✅ No memory leaks during repeated failures

---

## Load Test Comparison

Run this load test scenario to compare before/after:

```bash
# Test script
cd load-tests

# 1. Baseline with DB up (2 minutes)
locust -f locustfile.py --host=http://localhost --users=100 --spawn-rate=20 --run-time=2m --headless

# 2. Kill DB at 1 minute mark (in another terminal)
sleep 60 && docker-compose stop couchbase

# 3. Watch logs
docker-compose logs -f fhir-server | grep "ERROR"

# 4. Measure
# - Log file size
# - Memory usage
# - Error response times
# - HAProxy failover time
```

**Expected Improvements:**

- 📉 Log size: 95% reduction
- 📉 Memory usage: Stable (no leak)
- ⚡ Error response: < 1s (vs 30s timeout)
- ⚡ HAProxy failover: 15s (vs manual)

---

## Next Steps

1. ✅ Run all test scenarios
2. ✅ Verify metrics match expectations
3. ✅ Run load test comparison
4. ✅ Document any issues found
5. ✅ Configure monitoring/alerting on circuit breaker state
6. ✅ Set up log aggregation to track improvements
