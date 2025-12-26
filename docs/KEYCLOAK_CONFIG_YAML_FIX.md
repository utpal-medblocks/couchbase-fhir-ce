# Keycloak Configuration Fix: config.yaml Control

## Problem Identified

The Keycloak integration PR had a critical design flaw:

- **Hardcoded `use-keycloak: true`** in `application.yml`
- **Forced Keycloak on all users** regardless of their needs
- **Ignored config.yaml** - the user's primary configuration interface

This violated the project's design principle: **Users should only need to edit config.yaml, not application.yml**.

---

## Solution Implemented

### Changed Files

1. **`backend/src/main/resources/application.yml`**
2. **`backend/src/main/java/com/couchbase/admin/config/service/ConfigurationStartupService.java`**
3. **`config.yaml`** (template)

---

## How It Works Now

### Default Behavior (No Keycloak)

**config.yaml**:

```yaml
# Keycloak section commented out or enabled: false
keycloak:
  enabled: false
```

**Result**:

- ✅ Embedded Spring Authorization Server used
- ✅ Users stored in Couchbase (`fhir.Admin.users`)
- ✅ OAuth endpoints at `/oauth2/*`
- ✅ Works out of the box

---

### With Keycloak Enabled

**config.yaml**:

```yaml
keycloak:
  enabled: true
  url: "http://keycloak:8080/auth"
  realm: "fhir"
  adminUsername: "${KEYCLOAK_ADMIN_USERNAME:admin}"
  adminPassword: "${KEYCLOAK_ADMIN_PASSWORD:admin}"
  clientId: "fhir-server"
  clientSecret: "${KEYCLOAK_CLIENT_SECRET:your-secret}"
  publicUrl: "https://cbfhir.com/auth" # Optional, defaults to app.baseUrl/auth
```

**What Happens on Startup**:

1. `ConfigurationStartupService` reads `config.yaml`
2. Detects `keycloak.enabled: true`
3. Sets system properties:
   ```java
   System.setProperty("USE_KEYCLOAK", "true");
   System.setProperty("KEYCLOAK_URL", "...");
   System.setProperty("KEYCLOAK_REALM", "...");
   System.setProperty("KEYCLOAK_JWKS_URI", "...");
   // etc.
   ```
4. Spring Boot reads these properties during context initialization
5. `@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "true")` activates Keycloak beans
6. Embedded Authorization Server stays disabled

**Result**:

- ✅ External Keycloak handles OAuth
- ✅ Users managed in Keycloak
- ✅ OAuth endpoints at `/auth/realms/fhir/protocol/openid-connect/*`
- ✅ Enterprise features: SSO, MFA, LDAP, social login

---

## Configuration Properties Mapping

| config.yaml Key          | System Property           | Purpose                 |
| ------------------------ | ------------------------- | ----------------------- |
| `keycloak.enabled`       | `USE_KEYCLOAK`            | Master flag             |
| `keycloak.url`           | `KEYCLOAK_URL`            | Internal Keycloak URL   |
| `keycloak.realm`         | `KEYCLOAK_REALM`          | Realm name              |
| `keycloak.adminUsername` | `KEYCLOAK_ADMIN_USERNAME` | Admin API access        |
| `keycloak.adminPassword` | `KEYCLOAK_ADMIN_PASSWORD` | Admin API password      |
| `keycloak.clientId`      | `KEYCLOAK_CLIENT_ID`      | OAuth client ID         |
| `keycloak.clientSecret`  | `KEYCLOAK_CLIENT_SECRET`  | OAuth client secret     |
| `keycloak.publicUrl`     | `KEYCLOAK_PUBLIC_URL`     | External Keycloak URL   |
| (auto-generated)         | `KEYCLOAK_JWKS_URI`       | JWT validation endpoint |

---

## Code Changes

### 1. application.yml - Dynamic Defaults

**Before (Broken)**:

```yaml
app:
  security:
    use-keycloak: true # ❌ Hardcoded!

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://keycloak:8080/auth/realms/fhir/protocol/openid-connect/certs
```

**After (Fixed)**:

```yaml
app:
  security:
    use-keycloak: ${USE_KEYCLOAK:false} # ✅ Defaults to embedded server

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI:} # ✅ Set from config.yaml if enabled
```

### 2. ConfigurationStartupService.java - Read Keycloak Config

Added section after admin credentials loading:

```java
// Extract Keycloak configuration and set as system properties
@SuppressWarnings("unchecked")
Map<String, Object> keycloakConfig = (Map<String, Object>) yamlData.get("keycloak");
if (keycloakConfig != null && Boolean.TRUE.equals(keycloakConfig.get("enabled"))) {
    logger.info("🔐 Keycloak integration enabled in config.yaml");

    System.setProperty("USE_KEYCLOAK", "true");

    // Extract URL, realm, credentials, etc.
    // Construct JWKS URI automatically
    String jwksUri = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
    System.setProperty("KEYCLOAK_JWKS_URI", jwksUri);

    logger.info("✅ Keycloak configuration loaded from config.yaml");
} else {
    System.setProperty("USE_KEYCLOAK", "false");
    logger.info("ℹ️  Keycloak integration disabled - using embedded Spring Authorization Server");
}
```

### 3. config.yaml - Complete Template

```yaml
# Optional Keycloak Integration (Enterprise Identity Provider)
# When enabled, Keycloak handles OAuth, user management, SSO, MFA, etc.
# When disabled (or commented out), embedded Spring Authorization Server is used
keycloak:
  enabled: false # Set to true to enable Keycloak integration
  url: "http://keycloak:8080/auth"
  realm: "fhir"
  adminUsername: "${KEYCLOAK_ADMIN_USERNAME:admin}"
  adminPassword: "${KEYCLOAK_ADMIN_PASSWORD:admin}"
  clientId: "fhir-server"
  clientSecret: "${KEYCLOAK_CLIENT_SECRET:}"
  publicUrl: "https://cbfhir.com/auth" # Optional
```

---

## Testing

### Test 1: Default (Embedded Server)

**config.yaml**:

```yaml
# keycloak section commented out or:
keycloak:
  enabled: false
```

**Expected Startup Logs**:

```
ℹ️  Keycloak integration disabled - using embedded Spring Authorization Server
🔐 Initializing OAuth 2.0 Authorization Server with embedded configuration
✅ Generated RSA key pair for JWT signing (kid: ...)
```

**Verify**:

```bash
curl https://cbfhir.com/.well-known/smart-configuration | jq .authorization_endpoint
# Output: "https://cbfhir.com/oauth2/authorize"
```

### Test 2: Keycloak Enabled

**config.yaml**:

```yaml
keycloak:
  enabled: true
  url: "http://keycloak:8080/auth"
  realm: "fhir"
  adminUsername: "admin"
  adminPassword: "admin123"
  clientId: "fhir-server"
  clientSecret: "your-secret"
```

**Expected Startup Logs**:

```
🔐 Keycloak integration enabled in config.yaml
   🔗 Keycloak URL: http://keycloak:8080/auth
   🏛️  Keycloak Realm: fhir
   🔑 Keycloak Client ID: fhir-server
   🔑 Keycloak Client Secret: [REDACTED]
   🔐 Keycloak JWKS URI: http://keycloak:8080/auth/realms/fhir/protocol/openid-connect/certs
✅ Keycloak configuration loaded from config.yaml
🔐 Skipping embedded OAuth signing key initialization because Keycloak is enabled
```

**Verify**:

```bash
curl https://cbfhir.com/.well-known/smart-configuration | jq .authorization_endpoint
# Output: "https://cbfhir.com/auth/realms/fhir/protocol/openid-connect/auth"
```

---

## User Experience

### Download & Install (No Keycloak)

```bash
# 1. Clone repo
git clone https://github.com/your-org/couchbase-fhir-ce
cd couchbase-fhir-ce

# 2. Edit config.yaml (only Couchbase connection needed)
vim config.yaml

# 3. Apply and start
./scripts/apply-config.sh
docker-compose up -d

# ✅ Works immediately with embedded OAuth
```

### Enable Keycloak Later (Optional)

```bash
# 1. Edit config.yaml
vim config.yaml
# Set keycloak.enabled: true
# Add Keycloak URL, realm, credentials

# 2. Start Keycloak
./scripts/enable-keycloak.sh

# 3. Restart FHIR server
docker-compose restart backend

# ✅ Now using Keycloak
```

---

## Design Principles Restored

✅ **config.yaml is the source of truth**

- Users never need to touch `application.yml`
- All deployment config in one file

✅ **Sensible defaults**

- Embedded server by default
- Keycloak opt-in

✅ **Backward compatible**

- Existing deployments continue working
- No breaking changes

✅ **Environment variable support**

- Can still override via `USE_KEYCLOAK=true`
- Supports Docker secrets: `${KEYCLOAK_ADMIN_PASSWORD}`

✅ **Clear logging**

- Startup logs show which mode is active
- Keycloak config details logged (secrets redacted)

---

## Comparison with PR (Before Fix)

| Aspect                   | PR (Broken)          | After Fix            |
| ------------------------ | -------------------- | -------------------- |
| Default mode             | Keycloak ❌          | Embedded ✅          |
| User control             | application.yml ❌   | config.yaml ✅       |
| Out-of-box experience    | Requires Keycloak ❌ | Works immediately ✅ |
| Config location          | Hardcoded ❌         | config.yaml ✅       |
| Startup without Keycloak | Fails ❌             | Works ✅             |
| Backward compatible      | No ❌                | Yes ✅               |

---

## SMART Scope Fix Compatibility

The scope enforcement fix implemented earlier **works with both modes**:

```java
// SmartScopeValidator.java - Mode-agnostic
List<String> scopes = extractScopes(authentication);  // From JWT
boolean hasPermission = scopes.stream()
    .map(SmartScopes::parse)
    .anyMatch(scope -> scope.matches(resourceType, operation));
```

**Testing**:

- ✅ Test restricted access (2.3.02) with embedded server
- ✅ Test restricted access (2.3.02) with Keycloak
- ✅ Both should return 403 for non-granted resources

---

## Migration Guide

### For Existing Users (No Change Needed)

Your system continues working with embedded server:

```yaml
# config.yaml - no changes needed
admin:
  email: "admin@cbfhir.com"
  password: "Admin123!"
  name: "Admin"
# That's it!
```

### For New Users Wanting Keycloak

Add to `config.yaml`:

```yaml
keycloak:
  enabled: true
  url: "http://keycloak:8080/auth"
  realm: "fhir"
  adminUsername: "admin"
  adminPassword: "admin123"
  clientId: "fhir-server"
  clientSecret: "your-secret"
```

Then run:

```bash
./scripts/enable-keycloak.sh
```

---

## Summary

**Problem**: PR hardcoded Keycloak in `application.yml`, breaking user control

**Solution**:

1. Read Keycloak config from `config.yaml`
2. Default to embedded server (`use-keycloak: false`)
3. Set system properties when Keycloak enabled
4. Spring conditionally loads appropriate beans

**Result**:

- ✅ Users control Keycloak via config.yaml
- ✅ Embedded server is default
- ✅ No breaking changes
- ✅ SMART scope fix works with both modes

**Testing**:

- Compile successful ✅
- Ready for deployment ✅
- Need runtime testing with Inferno ⏳

---

**Date**: December 25, 2025  
**Version**: 0.9.305  
**Status**: ✅ Fixed - config.yaml now controls Keycloak
