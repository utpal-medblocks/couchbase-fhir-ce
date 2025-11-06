# FHIR Validator Memory Optimization

## Problem

- US Core validator consumes 95MB (15.57% of heap)
- Under load with 239+ concurrent transactions, this causes OOM
- Total validator memory: ~150MB (US Core + Basic validator)

## Root Cause

The `FhirValidator` loads the entire US Core 6.1.0 package into memory:

- Structure definitions
- Value sets (thousands of codes)
- Code systems
- Terminology data

## Solutions

### Option 1: Disable Validation Under Load (IMMEDIATE)

**Best for:** Production load scenarios where data is pre-validated

```bash
# Update bucket config to disable validation
curl -X PUT http://localhost:8080/admin/fhir-buckets/fhir/config \
  -H "Content-Type: application/json" \
  -d '{
    "fhirRelease": "Release 4",
    "validation": {
      "mode": "disabled",
      "profile": "none"
    }
  }'
```

**Benefits:**

- ✅ Saves ~150MB memory
- ✅ Eliminates validation CPU overhead (significant!)
- ✅ Faster bundle processing

**Trade-offs:**

- ❌ No validation of incoming data
- ❌ Bad data can enter the system

**When to use:**

- Load testing with known-good data
- Production with client-side validation
- High-throughput scenarios

### Option 2: Use Lenient Validation (MEDIUM)

**Best for:** Production with some validation, less memory

```json
{
  "validation": {
    "mode": "lenient",
    "profile": "none"
  }
}
```

**Benefits:**

- ✅ Basic FHIR R4 validation only (~50MB instead of 95MB)
- ✅ Catches major structural errors
- ✅ Much faster than US Core validation

### Option 3: Lazy Validator Loading (CODE CHANGE)

Modify `FhirConfig.java` to load validators on-demand:

```java
@Bean
@Primary
@Scope("prototype")  // Create new instance per request (NOT recommended - worse!)
public FhirValidator fhirValidator(FhirContext fhirContext) {
    // ... existing code
}

// BETTER: Use singleton but with lazy loading
@Bean
@Primary
public FhirValidator lazyFhirValidator(FhirContext fhirContext) {
    FhirValidator validator = fhirContext.newValidator();

    // Don't load US Core package until first use
    FhirInstanceValidator instanceValidator = new FhirInstanceValidator(
        // Lazy validation support
        new ValidationSupportChain() {
            private volatile boolean initialized = false;

            @Override
            public IBaseResource fetchStructureDefinition(String url) {
                if (!initialized) {
                    initializeUSCore();
                    initialized = true;
                }
                return super.fetchStructureDefinition(url);
            }
        }
    );

    validator.registerValidatorModule(instanceValidator);
    return validator;
}
```

**Benefits:**

- ✅ Memory only allocated when validation is needed
- ✅ Singleton still works

**Trade-offs:**

- ❌ First validation request is slow
- ❌ Complex code

### Option 4: Increase Heap Size (INFRASTRUCTURE)

If validation is required, increase JVM heap:

```bash
# Current (likely 512MB or 600MB)
java -Xmx512m -jar backend.jar

# Increase to 2GB
java -Xmx2048m -jar backend.jar
```

**Benefits:**

- ✅ Simple fix
- ✅ No code changes

**Trade-offs:**

- ❌ More memory usage
- ❌ Longer GC pauses
- ❌ Higher infrastructure costs

### Option 5: Conditional Validation (SMART SOLUTION)

Add a load-shedding mechanism that disables validation under high load:

```java
@Service
public class AdaptiveValidationService {
    private final AtomicInteger activeValidations = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_VALIDATIONS = 50;

    public boolean shouldValidate(FhirBucketConfig config) {
        // Always skip if disabled
        if (config.isValidationDisabled()) {
            return false;
        }

        // Load shedding: skip validation if too many in flight
        if (activeValidations.get() > MAX_CONCURRENT_VALIDATIONS) {
            logger.warn("⚠️ Skipping validation due to high load ({} active)",
                       activeValidations.get());
            return false;
        }

        return true;
    }

    public void trackValidation(Runnable validation) {
        activeValidations.incrementAndGet();
        try {
            validation.run();
        } finally {
            activeValidations.decrementAndGet();
        }
    }
}
```

## Recommended Approach

### For Load Testing:

**Use Option 1 (Disable Validation)**

- Set `validation.mode = "disabled"`
- Focus on testing throughput, not validation

### For Production:

**Combination:**

1. Use **lenient validation** (Option 2) as default
2. Implement **load shedding** (Option 5) for high traffic
3. **Increase heap** to 2GB (Option 4) if budget allows
4. Monitor memory and adjust

### Configuration Example:

```json
{
  "fhirRelease": "Release 4",
  "validation": {
    "mode": "lenient", // Basic FHIR R4 only
    "profile": "none" // Skip US Core (saves 45MB)
  },
  "logs": {
    "enableSystem": true,
    "enableCRUDAudit": false, // Reduce logging overhead
    "enableSearchAudit": false
  }
}
```

## Monitoring

Add memory monitoring to track validator impact:

```java
@Scheduled(fixedDelay = 60000)
public void logMemoryUsage() {
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    double percentUsed = (usedMemory * 100.0) / maxMemory;

    logger.info("💾 Memory: {}MB / {}MB ({}%)",
               usedMemory / 1024 / 1024,
               maxMemory / 1024 / 1024,
               String.format("%.1f", percentUsed));

    if (percentUsed > 80) {
        logger.warn("⚠️ Memory usage high - consider disabling validation");
    }
}
```

## Expected Results

| Configuration    | Memory Usage | Throughput  | Validation Quality |
| ---------------- | ------------ | ----------- | ------------------ |
| US Core Strict   | 95MB         | Low         | Excellent          |
| US Core Lenient  | 95MB         | Medium      | Good               |
| Basic R4 Lenient | 50MB         | High        | Moderate           |
| **Disabled**     | **0MB**      | **Highest** | **None**           |

## What's Next?

1. Check **Memory Analyzer Suspect #1** - that might be even bigger!
2. Set `validation.mode = "disabled"` for load test
3. Rerun load test and check if OOM is resolved
4. If still OOM, investigate Suspect #1
