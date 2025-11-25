# JWT Token Hardening & Bug Fixes

## Date: November 25, 2025

---

## 🐛 Critical Bug Fixed

### Issue: ClassCastException with JWT Claims

**Problem:**

```java
String audience = jwt.getClaim("aud");  // ❌ ClassCastException
```

Per JWT/OIDC spec, the `aud` (audience) claim can be **either**:

- A single string: `"client-id"`
- An array of strings: `["client-id-1", "client-id-2"]`

Spring's `jwt.getClaim()` is generic and attempts to cast to the expected type. When `aud` is an array but code expects a String, this throws:

```
java.util.ArrayList cannot be cast to java.lang.String
```

**Root Cause:**
Located in `JwtValidationInterceptor.java` where we checked token type by reading the `aud` claim unsafely.

**Solution:**
Use Spring Security's **typed accessors** instead of generic `getClaim()`:

```java
// ✅ Correct - uses typed accessor
List<String> audience = jwt.getAudience();  // Always returns List<String>
String jti = jwt.getId();                    // Always returns String
```

---

## 🔒 Hardening: Explicit Token Type Classification

### Problem with Implicit Detection

Previously, we inferred token type by checking if `aud` claim exists:

```java
// ❌ Brittle - relies on side effect
boolean isOAuth = (aud != null && !aud.isEmpty());
```

**Issues:**

- Fragile: depends on claim presence, not intent
- Unclear: code doesn't express "why" we're checking
- Risky: future tokens might break assumption

### Solution: Explicit `token_type` Claim

Now all tokens include an explicit `token_type` claim:

| Token Type    | token_type Value | Source                                        | Cache Validation               |
| ------------- | ---------------- | --------------------------------------------- | ------------------------------ |
| **OAuth**     | `"oauth"`        | Spring Authorization Server (`/oauth2/token`) | ❌ Skipped (managed by Spring) |
| **Admin UI**  | `"admin"`        | Admin login (`/api/auth/login`)               | ✅ Checked against cache       |
| **API Token** | `"api"`          | Tokens page (`/api/admin/tokens`)             | ✅ Checked against cache       |

### Implementation

**1. OAuth Tokens (`AuthorizationServerConfig.java`):**

```java
context.getClaims().claim("token_type", "oauth");
```

**2. Admin UI Tokens (`AuthController.java`):**

```java
JwtClaimsSet.builder()
    .id(UUID.randomUUID().toString())  // JTI added
    .claim("token_type", "admin")
    // ...
```

**3. API Tokens (`TokenService.java`):**

```java
JwtClaimsSet.builder()
    .id(jti)
    .claim("token_type", "api")
    // ...
```

**4. Validation (`JwtValidationInterceptor.java`):**

```java
// Primary: Check explicit token_type (hardened)
String tokenType = jwt.getClaimAsString("token_type");
boolean isOAuthToken = "oauth".equals(tokenType);

// Fallback: Infer from aud (backward compatibility)
if (tokenType == null) {
    List<String> aud = jwt.getAudience();
    isOAuthToken = (aud != null && !aud.isEmpty());
}
```

---

## 📊 Token Claim Comparison

### Before Hardening

```json
{
  // OAuth Token
  "sub": "user@example.com",
  "aud": "test-client",  // ⚠️ Used to infer type
  "scope": "patient/*.read openid",
  "jti": "abc-123",
  "iss": "http://localhost:8080"
}

{
  // Admin Token
  "sub": "admin@cb-fhir.com",
  // No aud claim  // ⚠️ Absence used to infer type
  "scope": "system/*.*",
  // No jti!  // ⚠️ Missing
  "email": "admin@cb-fhir.com"
}
```

### After Hardening

```json
{
  // OAuth Token
  "sub": "user@example.com",
  "aud": ["test-client"],  // ✅ Properly typed
  "token_type": "oauth",   // ✅ Explicit
  "scope": "patient/*.read openid",
  "jti": "abc-123",
  "iss": "http://localhost:8080",
  "fhirUser": "Practitioner/user@example.com"
}

{
  // Admin Token
  "sub": "admin@cb-fhir.com",
  "token_type": "admin",  // ✅ Explicit
  "scope": "system/*.*",
  "jti": "def-456",  // ✅ Added
  "email": "admin@cb-fhir.com"
}

{
  // API Token
  "sub": "user@example.com",
  "token_type": "api",    // ✅ Explicit
  "scope": "user/*.*",
  "jti": "ghi-789",
  "email": "user@example.com",
  "appName": "My FHIR App"
}
```

---

## 🔐 Security Benefits

### 1. **Type Safety**

- No more runtime ClassCastExceptions
- Code uses proper typed accessors
- Compiler catches type mismatches

### 2. **Explicit Intent**

- Token type is self-documenting
- No inference from side effects
- Clear separation of concerns

### 3. **Revocation Tracking**

- All tokens now have JTI (JWT ID)
- Admin/API tokens tracked in cache
- OAuth tokens managed by Spring

### 4. **Backward Compatibility**

- Fallback to `aud`-based detection
- Old tokens still work
- Gradual migration supported

### 5. **Future-Proof**

- Easy to add new token types
- No brittle assumptions
- Testable and maintainable

---

## 🧪 Testing Token Types

### Verify OAuth Token

```bash
# Get OAuth token
ACCESS_TOKEN="eyJ..."

# Decode and check claims
echo $ACCESS_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{token_type, aud, jti}'

# Expected:
# {
#   "token_type": "oauth",
#   "aud": ["test-client"],
#   "jti": "abc-123-..."
# }
```

### Verify Admin Token

```bash
# Login as admin
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cb-fhir.com","password":"Admin123!"}' \
  | jq -r '.token')

# Decode and check
echo $ADMIN_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{token_type, jti, scope}'

# Expected:
# {
#   "token_type": "admin",
#   "jti": "def-456-...",
#   "scope": "system/*.* user/*.*"
# }
```

### Verify API Token

```bash
# Create API token via Admin UI or API
# Then decode:
echo $API_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{token_type, jti, appName}'

# Expected:
# {
#   "token_type": "api",
#   "jti": "ghi-789-...",
#   "appName": "My App"
# }
```

---

## 📝 Code Changes Summary

### Files Modified

1. **`JwtValidationInterceptor.java`** - Fixed ClassCastException, added token_type detection
2. **`AuthorizationServerConfig.java`** - Added token_type="oauth" to OAuth tokens
3. **`AuthController.java`** - Added token_type="admin" and JTI to Admin tokens
4. **`TokenService.java`** - Added token_type="api" to API tokens

### Key Changes

| Change                  | File                            | Benefit                 |
| ----------------------- | ------------------------------- | ----------------------- |
| Use `jwt.getAudience()` | `JwtValidationInterceptor.java` | Type safety             |
| Use `jwt.getId()`       | `JwtValidationInterceptor.java` | Type safety             |
| Add `token_type` claim  | All token issuers               | Explicit classification |
| Add JTI to Admin tokens | `AuthController.java`           | Revocation tracking     |
| Fallback to `aud` check | `JwtValidationInterceptor.java` | Backward compatibility  |

---

## ✅ Validation Checklist

- [x] No more ClassCastException errors
- [x] All tokens have explicit token_type
- [x] All tokens have JTI for tracking
- [x] OAuth tokens work with FHIR API
- [x] Admin tokens work with Admin UI
- [x] API tokens work with FHIR API
- [x] Token revocation works for Admin/API tokens
- [x] OAuth tokens bypass cache check
- [x] Backward compatibility maintained

---

## 🔮 Future Improvements

1. **Different Issuers:**
   - OAuth: `iss: "http://localhost:8080"`
   - Admin: `iss: "http://localhost:8080/admin"`
   - API: `iss: "http://localhost:8080/api"`
2. **Token Families:**
   - Add `token_family` claim for related tokens
   - Track refresh token chains
3. **Enhanced Validation:**
   - Validate token_type against expected endpoint
   - Block Admin tokens from FHIR API
   - Block OAuth tokens from Admin endpoints

---

## 📚 References

- [JWT RFC 7519](https://tools.ietf.org/html/rfc7519) - JSON Web Token standard
- [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [OIDC Core Spec](https://openid.net/specs/openid-connect-core-1_0.html) - Audience claim definition

---

**Status:** ✅ Complete and Tested
**Date:** November 25, 2025
