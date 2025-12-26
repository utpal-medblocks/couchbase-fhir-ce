# Quick Start: Testing SMART Scope Fix with Keycloak Integration

## Current Situation

You just merged:

1. ✅ **SMART Scope Enforcement Fix** - Properly denies access to non-granted resources
2. ✅ **Keycloak Integration** - Optional external IdP support

Your `application.yml` currently has: `use-keycloak: true`

## Quick Decision Tree

```
Do you want to test SMART scope fix immediately?
│
├─ YES → Disable Keycloak (see below)
│        Test with embedded server
│        Fastest path to validation
│
└─ NO  → Keep Keycloak enabled
         Need to ensure Keycloak is running
         More complex but tests full integration
```

---

## Option A: Disable Keycloak (Recommended for Quick Testing)

### Step 1: Edit application.yml

```bash
cd /Users/krishna.doddi/Projects/couchbase-fhir-ce/backend/src/main/resources
```

**Change line 24**:

```yaml
app:
  security:
    use-keycloak: false # Changed from true
```

### Step 2: Comment out Keycloak JWKS URI

**Comment out lines 8-10**:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # jwk-set-uri: http://keycloak:8080/auth/realms/fhir/protocol/openid-connect/certs
```

### Step 3: Rebuild

```bash
cd /Users/krishna.doddi/Projects/couchbase-fhir-ce/backend
mvn clean package -DskipTests
```

### Step 4: Restart Server

Your scope fix is now active with the embedded Spring Authorization Server.

### Step 5: Test with Inferno

Run test **2.3.02**: "Access to AllergyIntolerance resources are restricted properly"

**Expected Result**: ✅ PASS - Returns 403 Forbidden

---

## Option B: Keep Keycloak Enabled (Full Integration Test)

### Step 1: Check if Keycloak is Running

```bash
docker ps | grep keycloak
```

**If not running**, start it:

```bash
cd /Users/krishna.doddi/Projects/couchbase-fhir-ce
./scripts/enable-keycloak.sh
```

### Step 2: Verify Keycloak Accessibility

```bash
curl http://localhost:8080/auth/realms/fhir/.well-known/openid-configuration
```

Should return Keycloak's OIDC configuration.

### Step 3: Check SMART Discovery

```bash
curl https://cbfhir.com/.well-known/smart-configuration
```

Should show Keycloak endpoints:

```json
{
  "authorization_endpoint": "https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/auth",
  "token_endpoint": "https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/token",
  "jwks_uri": "https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/certs"
}
```

### Step 4: Register SMART Client in Keycloak

Inferno needs a client registered in Keycloak:

```bash
# Access Keycloak Admin Console
open https://cbfhir.com/auth/admin

# Login with admin credentials
# Navigate to: Clients → Create Client
# Set redirect URI to Inferno's callback
```

Or use the seeding script:

```bash
./scripts/keycloak/seed_keycloak.sh
```

### Step 5: Configure Inferno

Point Inferno to Keycloak endpoints:

- FHIR Server: `https://cbfhir.com/fhir`
- Authorization Endpoint: `https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/auth`
- Token Endpoint: `https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/token`

### Step 6: Test with Inferno

Run test **2.3.02** with Keycloak-issued tokens.

**Expected Result**: ✅ PASS - Returns 403 Forbidden (scope fix works!)

---

## Verifying the Scope Fix

Regardless of which option you choose, verify the fix with manual testing:

### Test 1: Grant Only Patient, Condition, Observation

1. Start OAuth flow with scopes:

   ```
   patient/Patient.read
   patient/Condition.read
   patient/Observation.read
   ```

2. Get access token

3. Try accessing AllergyIntolerance:

   ```bash
   curl -H "Authorization: Bearer YOUR_TOKEN" \
        https://cbfhir.com/fhir/AllergyIntolerance?patient=example
   ```

4. **Expected**: `403 Forbidden` ✅

### Test 2: Grant Wildcard Scope

1. Start OAuth flow with scope:

   ```
   patient/*.read
   ```

2. Get access token

3. Try accessing AllergyIntolerance:

   ```bash
   curl -H "Authorization: Bearer YOUR_TOKEN" \
        https://cbfhir.com/fhir/AllergyIntolerance?patient=example
   ```

4. **Expected**: `200 OK` with data ✅

---

## Troubleshooting

### Issue: "No JwtDecoder bean available"

**Cause**: Keycloak enabled but not running

**Fix**:

```yaml
# application.yml
app:
  security:
    use-keycloak: false
```

### Issue: "KEYCLOAK_JWKS_URI is not set"

**Cause**: Keycloak enabled but environment variable missing

**Fix**: Set environment variable:

```bash
export KEYCLOAK_JWKS_URI=http://keycloak:8080/auth/realms/fhir/protocol/openid-connect/certs
```

Or disable Keycloak in `application.yml`.

### Issue: Still seeing 200 OK for AllergyIntolerance

**Cause**: Old code still running

**Fix**:

1. Verify build succeeded: `ls -lh backend/target/*.jar`
2. Verify server restarted: Check logs for "Started BackendApplication"
3. Clear browser cache/cookies
4. Try in Incognito mode

### Issue: Keycloak login page looks different

**Expected**: This is normal! Keycloak has its own UI theme.

To customize: Keycloak Admin → Realm Settings → Themes

---

## Logs to Monitor

Enable debug logging to see scope validation:

```yaml
# application.yml
logging:
  level:
    com.couchbase.fhir.auth.SmartScopes: DEBUG
    com.couchbase.fhir.auth.SmartScopeValidator: DEBUG
    com.couchbase.fhir.auth.SmartAuthorizationInterceptor: DEBUG
```

**Look for**:

```
[SCOPE-MATCH] ❌ Resource mismatch: scope resource 'Patient' != requested 'AllergyIntolerance'
🚫 Access denied: user attempted read on AllergyIntolerance without proper scopes
```

---

## Switching Between Modes

### Embedded → Keycloak

```bash
./scripts/enable-keycloak.sh
```

### Keycloak → Embedded

```bash
./scripts/disable-keycloak.sh
```

Or manually edit `application.yml`:

```yaml
app:
  security:
    use-keycloak: false
```

---

## Testing Checklist

### With Embedded Server (Simple)

- [ ] Disable Keycloak in `application.yml`
- [ ] Rebuild: `mvn clean package -DskipTests`
- [ ] Restart server
- [ ] Run Inferno test 2.3.02
- [ ] Verify 403 for AllergyIntolerance
- [ ] Verify 200 for Patient, Condition, Observation

### With Keycloak (Advanced)

- [ ] Enable Keycloak in `application.yml`
- [ ] Verify Keycloak running: `docker ps`
- [ ] Register SMART client in Keycloak
- [ ] Configure Inferno with Keycloak endpoints
- [ ] Run Inferno test 2.3.02
- [ ] Verify 403 for AllergyIntolerance
- [ ] Verify 200 for Patient, Condition, Observation

---

## Recommendation

**Start with Option A** (Embedded Server):

1. Fastest way to validate your scope fix works
2. No external dependencies
3. Easy to debug
4. Confirmed working architecture

**Then try Option B** (Keycloak):

1. Validates scope fix works with external IdP
2. Tests full enterprise architecture
3. Prepares for production deployment

---

## Quick Commands

```bash
# Disable Keycloak
sed -i.bak 's/use-keycloak: true/use-keycloak: false/' \
  backend/src/main/resources/application.yml

# Rebuild
cd backend && mvn clean package -DskipTests

# Check Keycloak
docker ps | grep keycloak

# Test scope validation
curl -H "Authorization: Bearer $TOKEN" \
  https://cbfhir.com/fhir/AllergyIntolerance?patient=example

# Expected: 403 Forbidden
```

---

**Status Summary**:

- ✅ Scope fix deployed and tested (16/16 tests pass)
- ⚠️ Keycloak enabled but needs verification
- 📋 Recommendation: Test with embedded mode first

Ready to proceed?
