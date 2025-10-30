# Quick Reference: Gateway Architecture

## Health Check URLs

```bash
# Readiness (for HAProxy) - Now actively tests DB to close circuit faster
curl http://localhost:8080/health/readiness

# Liveness (for Kubernetes)
curl http://localhost:8080/health/liveness

# Detailed status
curl http://localhost:8080/health | jq

# Force circuit breaker reset (manual recovery)
curl -X POST http://localhost:8080/health/circuit/reset
```

## HAProxy Stats

```bash
# Web UI
http://localhost/haproxy?stats
# User: admin, Pass: admin

# CSV export
curl -s http://admin:admin@localhost/haproxy?stats\;csv
```

## Log Patterns

### ✅ Good (After Gateway)

```
ERROR - ⚡ Circuit breaker OPENED - database unavailable
ERROR - 🔴 Database unavailable - returning 503
INFO  - ✅ Circuit breaker CLOSED - database recovered
```

### ❌ Bad (Before Gateway - Should Not See)

```
ERROR - No active connection found
java.lang.RuntimeException: FTS search failed
    at com.couchbase...
    ... 150 more lines ...
```

## Circuit Breaker States

| State                         | Meaning                     | Action         |
| ----------------------------- | --------------------------- | -------------- |
| `CLOSED`                      | Normal operation            | None needed    |
| `OPEN`                        | Database down, failing fast | Check DB       |
| Auto-closes after 30s timeout | Self-healing attempt        | Wait or fix DB |

## Common Commands

```bash
# Check circuit breaker state
curl http://localhost:8080/health | jq '.components.circuitBreaker.status'

# Check database status
curl http://localhost:8080/health | jq '.components.database.status'

# Restart circuit breaker (if stuck)
docker-compose restart fhir-server

# Test DB failure
docker-compose stop couchbase

# Test DB recovery
docker-compose start couchbase

# Watch logs in real-time
docker-compose logs -f fhir-server | grep -E "(ERROR|Circuit|Database)"

# Count errors (should be minimal)
docker-compose logs fhir-server | grep "ERROR" | wc -l
```

## Timings

- **Circuit breaker timeout:** 30 seconds
- **HAProxy check interval:** 5 seconds
- **HAProxy mark down:** 15 seconds (3 failures)
- **HAProxy mark up:** 10 seconds (2 successes)

## Expected Behavior Timeline

### Database Failure

```
T+0s:  Database goes down
T+0s:  Circuit breaker detects, opens immediately
T+0s:  Requests start returning 503 instantly
T+15s: HAProxy marks server as DOWN (after 3 checks)
T+15s: HAProxy stops routing traffic to this instance
```

### Database Recovery

```
T+0s:  Database comes back up
T+30s: Circuit breaker attempts to close (after timeout)
T+30s: First successful request closes circuit
T+40s: HAProxy marks server as UP (after 2 checks)
T+40s: HAProxy resumes routing traffic
```

## Integration Points

```java
// Services use gateway
@Autowired
private CouchbaseGateway couchbaseGateway;

// N1QL queries
QueryResult result = couchbaseGateway.query("default", sql);

// FTS searches
SearchResult result = couchbaseGateway.searchQuery("default", index, query, options);

// Transactions
Cluster cluster = couchbaseGateway.getClusterForTransaction("default");
```

## Monitoring

```bash
# Prometheus metrics (if configured)
curl http://localhost:8080/actuator/prometheus | grep circuit

# Health metrics
curl http://localhost:8080/actuator/health | jq

# Custom health endpoint
curl http://localhost:8080/health | jq '.components'
```

## Troubleshooting Quick Fixes

```bash
# Circuit stuck open? (Try manual reset first)
curl -X POST http://localhost:8080/health/circuit/reset

# Still stuck? Restart server
docker-compose restart fhir-server

# HAProxy not updating? (Wait 10s or restart)
docker-compose restart haproxy

# Clean restart everything
docker-compose down && docker-compose up -d

# Check health endpoint working
curl -v http://localhost:8080/health/readiness

# Check HAProxy can reach health endpoint
docker-compose exec haproxy wget -O- http://fhir-server:8080/health/readiness
```

## Recovery After Capella IP Unblock

```bash
# Fast recovery (10 seconds)
curl -X POST http://localhost:8080/health/circuit/reset
sleep 10
curl http://localhost/fhir/test/Patient  # Should work

# See RECOVERY_GUIDE.md for detailed procedures
```

## Files to Check

| File                                                                               | Purpose                     |
| ---------------------------------------------------------------------------------- | --------------------------- |
| `haproxy.cfg`                                                                      | HAProxy health check config |
| `backend/src/main/java/com/couchbase/fhir/resources/gateway/CouchbaseGateway.java` | Circuit breaker logic       |
| `backend/src/main/java/com/couchbase/admin/health/HealthController.java`           | Health endpoints            |
| `GATEWAY_ARCHITECTURE.md`                                                          | Full documentation          |
| `TESTING_GUIDE.md`                                                                 | Test scenarios              |

## Support

- Circuit breaker timeout: Adjust `CIRCUIT_RESET_TIMEOUT_MS` in `CouchbaseGateway.java`
- HAProxy timings: Adjust `inter`, `fall`, `rise` in `haproxy.cfg`
- Health check endpoint: Change URL in `haproxy.cfg` and `HealthController.java`
