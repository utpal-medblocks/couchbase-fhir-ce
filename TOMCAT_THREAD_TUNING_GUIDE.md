# Tomcat Thread Pool Tuning Guide

## Your Observation: RPS Dropped with 200 Concurrent Users

**Root Cause:** 50 threads can't efficiently handle 200 concurrent users!

### The Math

With Bundle transactions taking ~500ms each:

- 50 threads × 2 requests/sec/thread = **100 RPS max**
- 200 concurrent users → 150 users queued → **increased latency** → **lower RPS**

**Solution:** Increase threads to match your concurrency needs.

## Now You Can Tune Without Rebuilding!

I've made the settings configurable via environment variables:

### Quick Tuning (Edit docker-compose.yaml)

```yaml
environment:
  SERVER_TOMCAT_THREADS_MAX: 100 # ← Change this
  SERVER_TOMCAT_THREADS_MIN_SPARE: 20 # ← And this
  SERVER_TOMCAT_ACCEPT_COUNT: 200
  SERVER_TOMCAT_MAX_CONNECTIONS: 300
  SERVER_TOMCAT_CONNECTION_TIMEOUT: 20s
```

Then just restart (no rebuild needed!):

```bash
docker-compose restart fhir-server
```

## Recommended Settings by Load

### Low Load (50-100 concurrent users)

```yaml
SERVER_TOMCAT_THREADS_MAX: 50
SERVER_TOMCAT_THREADS_MIN_SPARE: 10
SERVER_TOMCAT_ACCEPT_COUNT: 100
SERVER_TOMCAT_MAX_CONNECTIONS: 200
```

**Memory:** ~50MB for threads

### Medium Load (100-200 concurrent users) ← **YOU ARE HERE**

```yaml
SERVER_TOMCAT_THREADS_MAX: 100 # Doubled
SERVER_TOMCAT_THREADS_MIN_SPARE: 20
SERVER_TOMCAT_ACCEPT_COUNT: 200
SERVER_TOMCAT_MAX_CONNECTIONS: 300
```

**Memory:** ~100MB for threads (with -Xss512k)

### High Load (200-400 concurrent users)

```yaml
SERVER_TOMCAT_THREADS_MAX: 150
SERVER_TOMCAT_THREADS_MIN_SPARE: 30
SERVER_TOMCAT_ACCEPT_COUNT: 300
SERVER_TOMCAT_MAX_CONNECTIONS: 500
```

**Memory:** ~150MB for threads

### Very High Load (400+ concurrent users)

```yaml
SERVER_TOMCAT_THREADS_MAX: 200 # Back to default
SERVER_TOMCAT_THREADS_MIN_SPARE: 50
SERVER_TOMCAT_ACCEPT_COUNT: 500
SERVER_TOMCAT_MAX_CONNECTIONS: 1000
```

**Memory:** ~200MB for threads

## How to Calculate Optimal Thread Count

### Formula

```
Optimal Threads ≈ Concurrent Users × (Request Time / Processing Time)
```

### For Your Case (Bundle Transactions)

- Request time: ~500ms
- Processing time (CPU work): ~100ms
- Waiting time (DB, I/O): ~400ms

```
Optimal Threads = 200 users × (500ms / 100ms) = 200 × 5 = 1000 threads
```

**But wait!** That's way too many. Reality:

- Most requests arrive in bursts
- You need to balance memory vs throughput
- **Rule of thumb: 0.5-1.0 thread per concurrent user** for I/O-bound workloads

For 200 concurrent users: **100-150 threads** is optimal.

## Memory Impact

With `-Xss512k` (thread stack size):

```
Thread Memory = Threads × 512KB × 2 (overhead)

50 threads  = 50 × 512KB × 2 = ~50MB
100 threads = 100 × 512KB × 2 = ~100MB
150 threads = 150 × 512KB × 2 = ~150MB
200 threads = 200 × 512KB × 2 = ~200MB
```

**Your 2GB heap can easily handle 150 threads!**

## Testing Different Configurations

### Test 1: 100 Threads (Recommended Start)

```bash
# Edit docker-compose.yaml
SERVER_TOMCAT_THREADS_MAX: 100

# Restart
docker-compose restart fhir-server

# Run load test with 200 users
locust -f locustfile.py --users 200 --spawn-rate 20
```

**Expected:** RPS should return to previous levels or better

### Test 2: 150 Threads (If Still Low)

```bash
SERVER_TOMCAT_THREADS_MAX: 150
docker-compose restart fhir-server
# Re-run load test
```

### Test 3: 200 Threads (Maximum)

```bash
SERVER_TOMCAT_THREADS_MAX: 200
SERVER_TOMCAT_THREADS_MIN_SPARE: 50
docker-compose restart fhir-server
# Re-run load test
```

## Monitoring

### Check Active Threads

```bash
# During load test
docker exec fhir-server jcmd 1 Thread.print | grep "http-nio" | wc -l
```

This shows how many Tomcat threads are active.

**If you see consistently max threads active → need more threads!**

### Check Queue Size

Add this to your logs to see queuing:

```bash
docker logs fhir-server 2>&1 | grep "WARN.*pool"
```

If you see warnings about full pool → increase threads or accept-count.

## Key Parameters Explained

### `SERVER_TOMCAT_THREADS_MAX`

- **What:** Maximum number of worker threads
- **Impact:** More threads = higher concurrency, more memory
- **Tune:** Set to 0.5-1.0× concurrent users

### `SERVER_TOMCAT_THREADS_MIN_SPARE`

- **What:** Minimum idle threads kept warm
- **Impact:** Faster response to sudden load spikes
- **Tune:** Set to ~20% of max threads

### `SERVER_TOMCAT_ACCEPT_COUNT`

- **What:** Queue size when all threads are busy
- **Impact:** How many requests can wait
- **Tune:** Set to 1-2× max threads

### `SERVER_TOMCAT_MAX_CONNECTIONS`

- **What:** Maximum simultaneous connections (not threads!)
- **Impact:** Prevents connection exhaustion
- **Tune:** Set to max threads + accept count

### `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS`

- **What:** Max requests per keep-alive connection
- **Impact:** Connection reuse efficiency
- **Tune:** Higher = better for high-RPS scenarios

## Troubleshooting

### RPS Still Low

1. **Check thread utilization:**

   ```bash
   docker exec fhir-server jcmd 1 Thread.print | grep "RUNNABLE" | wc -l
   ```

   If all threads RUNNABLE → increase threads

2. **Check if CPU-bound:**

   ```bash
   docker stats fhir-server
   ```

   If CPU at 100% → need more CPU cores, not more threads

3. **Check database:**
   - Couchbase might be the bottleneck
   - Check Couchbase query times

### Memory Issues

If you get OOM with more threads:

1. Reduce thread stack size further:

   ```
   -Xss256k  # Down from 512k
   ```

2. Or increase heap:
   ```
   -Xmx3g    # Up from 2g
   ```

## Recommended Configuration for Your Test

Based on 200 concurrent users:

```yaml
# docker-compose.yaml
environment:
  SERVER_TOMCAT_THREADS_MAX: 100 # Start here
  SERVER_TOMCAT_THREADS_MIN_SPARE: 20
  SERVER_TOMCAT_ACCEPT_COUNT: 200 # 2× threads
  SERVER_TOMCAT_MAX_CONNECTIONS: 300 # threads + accept
  SERVER_TOMCAT_CONNECTION_TIMEOUT: 20s
  SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS: 1000 # High reuse
```

**Memory usage:** ~100MB for threads (well within your 2GB heap)

## Testing Strategy

1. **Baseline:** Test with 100 threads, measure RPS
2. **Increase:** Test with 150 threads, measure RPS
3. **Validate:** If RPS increases, 100 was too low
4. **Find sweet spot:** Keep increasing until RPS plateaus
5. **Monitor:** Check memory and CPU usage

**The goal:** Maximum RPS without OOM or high latency

## Quick Reference Table

| Concurrent Users | Recommended Threads | Memory | Expected RPS\* |
| ---------------- | ------------------- | ------ | -------------- |
| 50               | 50                  | 50MB   | 100            |
| 100              | 75                  | 75MB   | 200            |
| 200              | 100                 | 100MB  | 400            |
| 300              | 150                 | 150MB  | 600            |
| 400              | 200                 | 200MB  | 800            |

\*Assumes 500ms average request time

## Summary

- ✅ **You can now tune without rebuilding** - just edit docker-compose.yaml and restart
- ✅ **Start with 100 threads** for 200 concurrent users
- ✅ **Monitor and adjust** based on actual RPS
- ✅ **Memory is not a problem** - you have 2GB heap, 100 threads = 100MB

**Go ahead and test with 100 threads now!** You should see your RPS return to previous levels. 🚀
