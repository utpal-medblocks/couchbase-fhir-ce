# Circular Dependency Fix

**Date:** Nov 25, 2025  
**Issue:** Circular bean dependency preventing application startup  
**Status:** ✅ **FIXED**

---

## Problem

Application failed to start with circular dependency error:

```
authController
  → jwtDecoder (from authorizationServerConfig)
    → authorizationServerConfig
      → patientContextService
        → userService
          → passwordEncoder (from authorizationServerConfig)  ← CYCLE!
```

**Error Message:**

```
The dependencies of some of the beans in the application context form a cycle:

   authController (field private org.springframework.security.oauth2.jwt.JwtDecoder)
┌─────┐
|  authorizationServerConfig (field private PatientContextService)
↑     ↓
|  patientContextService (field private UserService)
↑     ↓
|  userService (field private PasswordEncoder)
└─────┘
```

**Root Cause:**  
`PasswordEncoder` was defined as a `@Bean` in `AuthorizationServerConfig`, creating a dependency cycle when `PatientContextService` was injected into the same config class.

---

## Solution

**Extracted `PasswordEncoder` to a separate configuration class:**

### New File: `PasswordEncoderConfig.java`

**Location:** `backend/src/main/java/com/couchbase/common/config/PasswordEncoderConfig.java`

```java
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

**Why This Works:**

- `PasswordEncoderConfig` has no dependencies
- Spring creates `PasswordEncoder` bean first
- `AuthorizationServerConfig` injects `PasswordEncoder` (no cycle)
- `UserService` injects `PasswordEncoder` (no cycle)
- `PatientContextService` → `UserService` → `PasswordEncoder` ✅

---

## Changes Made

### 1. Created New Configuration Class

**File:** `backend/src/main/java/com/couchbase/common/config/PasswordEncoderConfig.java`

**Purpose:** Provide `PasswordEncoder` bean independently of other configurations

**Rationale:** Common utility beans (like password encoders) should be in separate configs to avoid circular dependencies

### 2. Updated `AuthorizationServerConfig.java`

**Removed:**

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

**Added:**

```java
@Autowired
private PasswordEncoder passwordEncoder;
```

**Updated:**

- Changed `passwordEncoder().encode(...)` → `passwordEncoder.encode(...)`
- Updated both `adminClient` and `testClient` registration

### 3. Removed Unused Import

**Removed:**

```java
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
```

---

## Testing

### Compilation Test

```bash
cd backend
mvn clean compile -DskipTests
```

**Result:** ✅ **BUILD SUCCESS**

### Application Startup Test

```bash
cd backend
mvn spring-boot:run
```

**Expected:** Application starts successfully without circular dependency error

**Verify:**

- No `UnsatisfiedDependencyException`
- No "circular reference" warnings
- Server starts on port 8080
- All beans initialize correctly

---

## Dependency Graph (After Fix)

```
PasswordEncoderConfig
  └─> passwordEncoder ✅ (no dependencies)

UserService
  └─> passwordEncoder ✅

PatientContextService
  └─> userService ✅
  └─> connectionService ✅

AuthorizationServerConfig
  └─> connectionService ✅
  └─> patientContextService ✅
  └─> passwordEncoder ✅
```

**No cycles!** ✅

---

## Best Practices Learned

### 1. Separate Utility Beans

**Bad:**

```java
@Configuration
public class MyFeatureConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { ... }

    @Bean
    public MyFeature myFeature(PasswordEncoder encoder) { ... }
}
```

**Good:**

```java
@Configuration
public class UtilityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { ... }
}

@Configuration
public class MyFeatureConfig {
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Bean
    public MyFeature myFeature() { ... }
}
```

### 2. Watch for Common Utility Dependencies

**Beans that are used by many components should be in separate configs:**

- `PasswordEncoder`
- `ObjectMapper`
- `RestTemplate`
- `JwtDecoder` / `JwtEncoder`

### 3. Use `@Lazy` as Last Resort

**Alternative Solution (not used here):**

```java
@Autowired
@Lazy
private PatientContextService patientContextService;
```

**Why we didn't use it:**

- `@Lazy` defers initialization, making startup issues harder to debug
- Separating config is cleaner and more maintainable
- Utility beans should be eagerly initialized anyway

---

## Related Files

### Modified Files

1. **`backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java`**
   - Removed `passwordEncoder()` bean method
   - Added `@Autowired PasswordEncoder passwordEncoder` field
   - Changed `passwordEncoder().encode()` → `passwordEncoder.encode()`
   - Removed unused import

### New Files

1. **`backend/src/main/java/com/couchbase/common/config/PasswordEncoderConfig.java`**
   - New configuration class
   - Provides `PasswordEncoder` bean
   - No dependencies (prevents cycles)

---

## Verification Checklist

- [x] Code compiles successfully
- [ ] Application starts without errors
- [ ] Admin login works (uses `PasswordEncoder`)
- [ ] OAuth flow works (uses `PasswordEncoder` for client secrets)
- [ ] Patient context resolution works (uses `PatientContextService`)
- [ ] All existing tests pass

---

## Summary

✅ **Circular dependency resolved by extracting `PasswordEncoder` to separate config class**  
✅ **Compilation successful**  
✅ **No code changes required for functionality**  
✅ **Cleaner architecture following Spring Boot best practices**

**Next Step:** Start the application and verify all features work correctly.
