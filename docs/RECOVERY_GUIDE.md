# Recovery Guide: Capella IP Blocking Scenario

## Problem: HAProxy Not Auto-Recovering After Capella IP Unblock

When Capella blocks your IP and you later unblock it, the system may not automatically recover because:

1. **Circuit breaker opens** (30s timeout)
2. **HAProxy marks server DOWN** after 3 failed health checks (15s)
3. **HAProxy stops sending health checks** to DOWN servers
4. Even when Capella allows IP again, circuit stays open for 30s
5. HAProxy never sees successful health checks, so server stays DOWN

## Solution Implemented

### 1. Smart Health Check (Automatic)

The `/health/readiness` endpoint now actively tests the database when the circuit is open:

```java
// If circuit is open but connection exists, try a lightweight test
if (dbAvailable && circuitOpen) {
    try {
        couchbaseGateway.query("default", "SELECT 1 LIMIT 1");
        // Success! Circuit closes automatically
        return 200 OK;
    } catch (Exception e) {
        // Still failing
        return 503;
    }
}
```

**Benefit:** Health checks actively try to close the circuit, enabling faster recovery.

### 2. Manual Circuit Reset (For Emergencies)

New endpoint to force circuit reset:

```bash
# Force circuit breaker to test connectivity
curl -X POST http://localhost:8080/health/circuit/reset

# Response on success:
{
  "success": true,
  "message": "Circuit breaker reset successful - database is accessible",
  "wasOpen": true,
  "timestamp": 1234567890
}
```

**Benefit:** Immediate recovery without waiting for HAProxy health checks.

## Recovery Procedures

### Scenario A: Automatic Recovery (Normal)

**Timeline after Capella IP unblocked:**

```
T+0s:   Capella allows IP
T+0s:   Circuit breaker still open (30s timeout not elapsed)
T+5s:   HAProxy health check hits /health/readiness
T+5s:   Health check tests DB with "SELECT 1"
T+5s:   Test succeeds, circuit closes automatically
T+5s:   Health check returns 200 OK
T+10s:  Second health check succeeds
T+10s:  HAProxy marks server UP (after 2 successes)
T+10s:  Traffic resumes
```

**Total recovery time: ~10 seconds**

### Scenario B: Manual Recovery (Fast)

If you need immediate recovery:

```bash
# 1. Verify Capella IP is unblocked
# Check Capella console

# 2. Manually reset circuit
curl -X POST http://localhost:8080/health/circuit/reset

# 3. Check status
curl http://localhost:8080/health
# Should show circuitBreaker: "CLOSED"

# 4. Wait for HAProxy (10 seconds)
sleep 10

# 5. Verify HAProxy status
curl http://admin:admin@localhost/haproxy?stats
# Server should show UP
```

**Total recovery time: ~10 seconds**

### Scenario C: Forced HAProxy Recovery

If HAProxy is stuck not sending health checks:

```bash
# 1. Reset circuit breaker
curl -X POST http://localhost:8080/health/circuit/reset

# 2. Manually tell HAProxy to enable server
# Via stats interface:
# http://localhost/haproxy?stats
# Click "enable" next to the DOWN server

# OR restart HAProxy
docker-compose restart haproxy
```

**Total recovery time: ~2 seconds**

## Monitoring Commands

### Check Current Status

```bash
# Full health status
curl http://localhost:8080/health | jq

# Just circuit breaker state
curl http://localhost:8080/health | jq '.components.circuitBreaker.status'

# HAProxy server status
curl -s http://admin:admin@localhost/haproxy?stats\;csv | grep fhir-server | cut -d, -f18
```

### Watch Recovery in Real-Time

```bash
# Watch health status
watch -n 1 'curl -s http://localhost:8080/health | jq ".components"'

# Watch HAProxy logs
docker-compose logs -f haproxy | grep health

# Watch FHIR server logs
docker-compose logs -f fhir-server | grep -E "(Circuit|Database|Readiness)"
```

## Troubleshooting

### Issue: Circuit won't close after manual reset

**Symptom:**

```bash
curl -X POST http://localhost:8080/health/circuit/reset
# Returns: "success": false
```

**Cause:** Capella IP is still blocked or network issue

**Fix:**

```bash
# 1. Verify Capella allows your IP
# Check Capella console > Security > Allowed IPs

# 2. Test connectivity directly
docker-compose exec fhir-server curl -v https://your-cluster.cloud.couchbase.com

# 3. Check connection string in config
cat config.yaml | grep couchbaseConnectionString
```

### Issue: HAProxy shows server DOWN but health check returns 200

**Symptom:**

```bash
curl http://localhost:8080/health/readiness
# Returns 200 OK

curl http://admin:admin@localhost/haproxy?stats
# Shows: backend fhir-server DOWN
```

**Cause:** HAProxy marked server as DOWN and hasn't re-checked yet

**Fix:**

```bash
# Option 1: Wait 10 seconds for next health check cycle

# Option 2: Manually enable in HAProxy stats UI
# http://localhost/haproxy?stats
# Click "enable" next to server

# Option 3: Restart HAProxy
docker-compose restart haproxy
```

### Issue: Health checks succeed but requests still fail with 503

**Symptom:**

```bash
curl http://localhost:8080/health/readiness
# Returns 200 OK

curl http://localhost/fhir/test/Patient
# Returns 503 from HAProxy
```

**Cause:** HAProxy still shows server as DOWN

**Check:**

```bash
# View HAProxy backend status
docker-compose logs haproxy | tail -20

# Look for: backend-fhir-server/<NOSRV>
# This means no servers are available
```

**Fix:**

```bash
# Check HAProxy health check config
cat haproxy.cfg | grep -A 5 "option httpchk"

# Should see:
# option httpchk GET /health/readiness
# http-check expect status 200

# Reload HAProxy config
docker-compose restart haproxy
```

## Prevention: Faster Recovery Settings

### Option 1: Faster Health Checks

Edit `haproxy.cfg`:

```haproxy
# Change from:
server backend fhir-server:8080 check inter 5s fall 3 rise 2

# To (faster checks):
server backend fhir-server:8080 check inter 2s fall 2 rise 2

# Result:
# - Check every 2 seconds (instead of 5)
# - Mark DOWN after 4 seconds (instead of 15)
# - Mark UP after 4 seconds (instead of 10)
```

**Trade-off:** More health check traffic, but faster failover/recovery

### Option 2: Shorter Circuit Timeout

Edit `CouchbaseGateway.java`:

```java
// Change from:
private static final long CIRCUIT_RESET_TIMEOUT_MS = 30_000; // 30 seconds

// To:
private static final long CIRCUIT_RESET_TIMEOUT_MS = 10_000; // 10 seconds
```

**Trade-off:** Circuit tries to close sooner (may retry too early)

### Option 3: Health Check Retries

Edit `haproxy.cfg`:

```haproxy
backend backend-fhir-server
    option httpchk GET /health/readiness
    http-check expect status 200

    # Add retry logic
    retries 2
    option redispatch

    server backend fhir-server:8080 check inter 2s fall 2 rise 2 on-error mark-down
```

## Best Practices

### 1. Monitor Circuit Breaker State

Set up alerts:

```bash
# Alert when circuit opens
curl http://localhost:8080/health | jq '.components.circuitBreaker.status' | grep "OPEN" && \
  echo "ALERT: Circuit breaker is OPEN"

# Alert when circuit is open for > 1 minute
```

### 2. Log Capella IP Changes

When you unblock IP in Capella:

```bash
# Immediately test and reset
curl -X POST http://localhost:8080/health/circuit/reset

# Log the action
echo "$(date): Unblocked IP in Capella, circuit reset" >> recovery.log
```

### 3. Automate Recovery

Create a recovery script:

```bash
#!/bin/bash
# recovery.sh - Auto-recover from Capella IP block

echo "Attempting automatic recovery..."

# Test circuit reset
response=$(curl -s -X POST http://localhost:8080/health/circuit/reset)
success=$(echo $response | jq -r '.success')

if [ "$success" = "true" ]; then
    echo "✅ Circuit breaker reset successful"
    echo "⏳ Waiting 10s for HAProxy to mark server UP..."
    sleep 10

    # Test FHIR endpoint
    if curl -s http://localhost/fhir/test/metadata > /dev/null; then
        echo "✅ FHIR server is responding - recovery complete!"
        exit 0
    else
        echo "⚠️  FHIR server not responding yet, try manual HAProxy reset"
        exit 1
    fi
else
    echo "❌ Circuit reset failed - Capella may still be blocking IP"
    echo "Error: $(echo $response | jq -r '.message')"
    exit 1
fi
```

Usage:

```bash
chmod +x recovery.sh
./recovery.sh
```

## Summary

**Automatic Recovery (New):**

- Health checks actively test DB when circuit is open
- Recovery time: ~10 seconds after IP unblock

**Manual Recovery:**

```bash
curl -X POST http://localhost:8080/health/circuit/reset
# Wait 10 seconds for HAProxy
```

**Emergency Recovery:**

```bash
docker-compose restart haproxy fhir-server
```

**Monitoring:**

```bash
# Check status
curl http://localhost:8080/health | jq '.components'

# Watch recovery
watch -n 1 'curl -s http://localhost:8080/health | jq ".components.circuitBreaker"'
```
