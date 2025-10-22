# OOM Fix: Thread Pool Tuning

## Root Cause Analysis

**Memory Analyzer reveals:**

- **Suspect #1:** 202 Tomcat threads = 122MB (19.95%)
- **Suspect #2:** FhirValidator = 95MB (15.57%)
- **Total heap shown:** ~610MB (not the configured 2GB!)

**The Problem:**

- 202 HTTP threads (Tomcat at default 200 thread limit)
- 239+ transaction threads (Couchbase)
- = **441+ total threads consuming ~529MB just for stacks/overhead**
- Only ~80MB left for application logic → OOM!

## Why Heap Shows 610MB Instead of 2GB

The heap dump might have been:

1. Taken early before heap grew
2. Showing committed memory, not max memory
3. Or heap growth is constrained

**Check actual heap size:**

```bash
# In the heap dump tool
# Look for "Max Heap Size" vs "Used Heap Size"
# It should show -Xmx2g (2GB max)
```

## Solutions

### Solution 1: Reduce Tomcat Thread Pool (CRITICAL)

**Problem:** 202 threads = 202MB+ in stack space alone

**Fix:** Reduce Tomcat threads to match your concurrency needs

Add to `backend/src/main/resources/application.yml`:

```yaml
server:
  tomcat:
    threads:
      max: 50 # Reduce from 200 to 50
      min-spare: 10 # Minimum idle threads
    accept-count: 100 # Queue size when all threads busy
    max-connections: 200 # Max simultaneous connections
    connection-timeout: 20s
```

**Benefits:**

- ✅ Saves ~150MB (152 fewer threads × 1MB stack each)
- ✅ Still handles 50 concurrent requests (usually enough!)
- ✅ Requests queue when all threads busy (backpressure)

**For high load scenarios:** Use 100 threads max (not 200)

### Solution 2: Optimize Couchbase Transaction Thread Usage

**Problem:** 239+ transaction threads active simultaneously

**Check transaction timeout:**

Add to `backend/src/main/resources/application.properties`:

```properties
# Couchbase transaction timeout (reduce from default 15s to 5s)
couchbase.transaction.timeout=5s

# Reduce transaction cleanup interval
couchbase.transaction.cleanup-window=5s
```

**Benefits:**

- ✅ Transactions timeout faster → fewer threads piled up
- ✅ Failed transactions cleaned up sooner

### Solution 3: Increase Thread Stack Size Reduction

**Problem:** Each thread uses 1MB stack (default)

**Fix:** Reduce thread stack size if your operations don't need deep recursion

Update `docker-compose.yaml`:

```yaml
JAVA_TOOL_OPTIONS: >-
  -Xms1g
  -Xmx2g
  -Xss512k                    # ← Reduce stack from 1MB to 512KB
  -XX:MaxDirectMemorySize=512m
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/app/logs/heap.hprof
  -XX:+ExitOnOutOfMemoryError
  -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
  -XX:NativeMemoryTracking=summary
  -XX:StartFlightRecording=name=background,filename=/app/logs/continuous.jfr,maxsize=500m,maxage=4h,disk=true
  -XX:FlightRecorderOptions=stackdepth=128
```

**Benefits:**

- ✅ 441 threads × 512KB = 221MB (vs 441MB before)
- ✅ Saves ~220MB!

**Risk:**

- ❌ StackOverflowError if you have deep recursion (unlikely in FHIR processing)

### Solution 4: Verify Heap is Actually 2GB

**Add monitoring at startup:**

Create `backend/src/main/java/com/couchbase/common/diagnostics/MemoryDiagnostics.java`:

```java
package com.couchbase.common.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MemoryDiagnostics implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(MemoryDiagnostics.class);

    @Override
    public void run(ApplicationArguments args) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        logger.info("=== JVM Memory Configuration ===");
        logger.info("Max Heap (-Xmx):     {} MB", maxMemory / 1024 / 1024);
        logger.info("Total Heap:          {} MB", totalMemory / 1024 / 1024);
        logger.info("Used Heap:           {} MB", usedMemory / 1024 / 1024);
        logger.info("Free Heap:           {} MB", freeMemory / 1024 / 1024);
        logger.info("Available for Heap:  {} MB", (maxMemory - usedMemory) / 1024 / 1024);

        // Thread count
        int threadCount = Thread.activeCount();
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        }
        int allThreads = rootGroup.activeCount();

        logger.info("=== Thread Information ===");
        logger.info("Active threads:      {}", allThreads);
        logger.info("Estimated thread overhead: ~{} MB ({}KB per thread)",
                   (allThreads * 1024) / 1024, 1024);

        // Check if we're memory constrained
        double usedPercent = (usedMemory * 100.0) / maxMemory;
        if (usedPercent > 50) {
            logger.warn("⚠️ Already using {}% of max heap at startup!",
                       String.format("%.1f", usedPercent));
        }

        logger.info("===============================");
    }
}
```

**This will log:**

- Actual max heap (-Xmx value)
- Current heap usage
- Thread count and estimated overhead
- Warning if you're already using >50% at startup

### Solution 5: Use Virtual Threads (Java 21+)

If you can upgrade to Java 21, use virtual threads:

```yaml
# In application.yml
spring:
  threads:
    virtual:
      enabled: true
```

**Benefits:**

- ✅ Virtual threads use ~1KB stack (vs 1MB)
- ✅ Can have 10,000+ concurrent threads
- ✅ Massive memory savings

**Requires:**

- Java 21+ (you're on Java 17)
- Spring Boot 3.2+

## Recommended Configuration

### Immediate Fix (No Code Changes)

**File: `backend/src/main/resources/application.yml`**

```yaml
server:
  tomcat:
    threads:
      max: 50 # Reduce from 200 → saves 150MB
      min-spare: 10
    accept-count: 100
    max-connections: 200
    connection-timeout: 20s
```

**File: `docker-compose.yaml`**

```yaml
JAVA_TOOL_OPTIONS: >-
  -Xms1g
  -Xmx2g
  -Xss512k                    # ← ADD THIS: reduce stack size
  -XX:MaxDirectMemorySize=512m
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/app/logs/heap.hprof
  -XX:+ExitOnOutOfMemoryError
  -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
  -XX:NativeMemoryTracking=summary
```

### Expected Memory Savings

| Component                | Before | After | Savings    |
| ------------------------ | ------ | ----- | ---------- |
| Tomcat threads (200→50)  | 200MB  | 50MB  | **-150MB** |
| Thread stack (1MB→512KB) | 441MB  | 221MB | **-220MB** |
| **Total savings**        |        |       | **~370MB** |

This frees up **370MB** for validators, application logic, and buffer space!

## Testing

1. **Apply changes**
2. **Restart containers:**

   ```bash
   docker-compose down
   docker-compose up -d
   ```

3. **Check logs for memory diagnostics:**

   ```bash
   docker logs fhir-server 2>&1 | grep "Memory Configuration"
   ```

4. **Run load test again:**

   ```bash
   # Your load test command
   locust -f locustfile.py --users 100 --spawn-rate 10
   ```

5. **Monitor thread count:**
   ```bash
   # Check active threads
   docker exec fhir-server jcmd 1 Thread.print | grep "Thread" | wc -l
   ```

## Validation

After applying fixes, you should see:

- ✅ Max heap shows 2048MB (2GB)
- ✅ Tomcat threads max out at 50 (not 202)
- ✅ Total threads < 150 under load (not 441)
- ✅ Memory usage stays under 70% even under load
- ✅ No OOM errors

## If Still Getting OOM

Check these:

1. **Native memory leak:** Transaction threads might be leaking

   ```bash
   docker exec fhir-server jcmd 1 VM.native_memory summary
   ```

2. **Direct buffer leak:** Check MaxDirectMemorySize usage

   ```bash
   docker exec fhir-server jcmd 1 VM.native_memory detail
   ```

3. **Thread leak:** Transactions not completing
   ```bash
   docker exec fhir-server jcmd 1 Thread.print | grep "cb-txn"
   ```

If you see 239+ `cb-txn` threads, you have a **transaction leak** - transactions are not completing/timing out properly.

## Next Steps

1. Apply Solution 1 (reduce Tomcat threads)
2. Apply Solution 3 (reduce stack size)
3. Add Solution 4 (memory diagnostics) to verify heap
4. Retest and check if OOM is resolved

Let me know what the memory diagnostics show at startup!
