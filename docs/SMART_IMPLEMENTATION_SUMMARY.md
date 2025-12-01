# SMART on FHIR Implementation Summary

## Overview

The Couchbase FHIR Server now supports SMART on FHIR authorization using OAuth 2.0 with Spring Authorization Server integrated directly into the backend application. This implementation provides secure, scope-based access control for FHIR resources while maintaining a separate authentication mechanism for the Admin UI.

## Architecture

### Two Separate Authentication Systems

1. **Admin UI Authentication** (`/api/admin/*`)

   - Custom JWT tokens using JJWT library
   - Credentials from `config.yaml`
   - Stateless session management
   - For Admin UI dashboard access only

2. **FHIR API OAuth 2.0** (`/fhir/*`)
   - Spring Authorization Server (integrated)
   - SMART on FHIR scopes
   - RSA-signed JWT access tokens
   - For FHIR resource access from external applications

### Security Filter Chain Order

Spring Security uses multiple ordered filter chains to handle different authentication mechanisms:

```
@Order(1): OAuth 2.0 Server Endpoints (/oauth2/*, /.well-known/*)
@Order(2): Admin UI API (/api/**)
@Order(3): FHIR API (/fhir/**)
@Order(4): Default (login page, actuator, public resources)
```

## Key Components

### 1. AuthorizationServerConfig.java

**Purpose**: Configures Spring Authorization Server with OAuth 2.0 endpoints

**Key Features**:

- Uses `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity()` for proper endpoint wiring
- Registers test client with SMART scopes
- Generates RSA key pair for JWT signing
- Customizes JWT tokens with SMART-specific claims (`patient`, `fhirUser`)
- Extracts OAuth issuer from `app.baseUrl` in `config.yaml`

**Critical Fix**: Changed from manually configuring `OAuth2AuthorizationServerConfigurer` to using Spring's canonical pattern with `applyDefaultSecurity()`. This ensures all OAuth endpoints (`/oauth2/authorize`, `/oauth2/token`, etc.) are properly mapped and functional.

### 2. SecurityConfig.java

**Purpose**: Configures Spring Security filter chains for different authentication requirements

**FHIR Filter Chain (`@Order(3)`)** - Three Critical Configurations:

1. **`AntPathRequestMatcher` for HAPI FHIR Servlet**

   ```java
   .securityMatcher(new AntPathRequestMatcher("/fhir/**"))
   ```

   Ensures the custom HAPI FHIR servlet mapping is properly secured.

2. **OAuth2 Resource Server with JwtDecoder**

   ```java
   .oauth2ResourceServer(oauth2 -> oauth2
       .jwt(jwt -> jwt.decoder(jwtDecoder))
   )
   ```

   Configures Bearer token authentication for FHIR endpoints.

3. **Disable Anonymous Authentication**
   ```java
   .anonymous(anonymous -> anonymous.disable())
   ```
   **CRITICAL**: Prevents Spring Security from creating `AnonymousAuthenticationToken` for unauthenticated requests. Without this, requests without valid JWTs would still reach the FHIR server as "anonymous" users.

**Why This Matters**: Before disabling anonymous auth, requests without valid Bearer tokens would:

- Pass through Spring Security as `AnonymousAuthenticationToken`
- Reach the HAPI FHIR interceptor
- Get rejected by `SmartAuthorizationInterceptor` with a confusing error

After disabling anonymous auth:

- Invalid/missing tokens are rejected at the Spring Security layer (401)
- Valid tokens create `JwtAuthenticationToken` with scopes
- Interceptor sees real authentication and validates scopes

### 3. SmartScopes.java

**Purpose**: Defines SMART on FHIR scope constants

**Supported Scopes**:

- `patient/*.read` / `patient/*.write` - Patient-context resource access
- `user/*.read` / `user/*.write` - User-context resource access
- `system/*.read` / `system/*.write` - System-context resource access
- `openid`, `profile`, `fhirUser`, `online_access` - OIDC and SMART launch scopes

### 4. SmartAuthorizationInterceptor.java

**Purpose**: HAPI FHIR interceptor that enforces SMART scopes on resource operations

**How It Works**:

1. Hooks into `SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED` (after route selection, before handler invocation)
2. Retrieves Spring Security `Authentication` from `SecurityContextHolder`
3. Checks if user has required scopes for the resource type and operation
4. Throws `AuthenticationException` if access denied

**Special Cases**:

- Allows public access to `/fhir/metadata` (checked first, before authentication)
- Validates scopes for CREATE, UPDATE, DELETE, READ operations
- Skips non-resource operations (search, batch, transaction - handled by resource-level checks)

### 5. SmartScopeValidator.java

**Purpose**: Validates if an authenticated user has the necessary scopes

**Validation Logic**:

```
patient/*.read → allows READ on all patient resources
patient/Observation.read → allows READ only on Observation resources
user/Observation.* → allows READ and WRITE on Observation in user context
```

### 6. USCoreCapabilityProvider.java (Updated)

**Purpose**: Generates FHIR CapabilityStatement advertising SMART support

**Additions**:

- Added `security` extensions with `OAuth` service type
- Added SMART extension with OAuth endpoint URLs
- Lists all supported SMART scopes
- Advertises conformance to SMART App Launch Framework

### 7. LoginController.java + login.html

**Purpose**: Server-side login page for OAuth authorization flow

**Features**:

- Thymeleaf template with modern, responsive design
- Purple gradient theme matching FHIR branding
- CSRF protection
- Form login for OAuth `/oauth2/authorize` flow

## Critical Fixes That Made It Work

### Fix #1: OAuth Endpoint Wiring

**Problem**: `/oauth2/authorize` returned 404 Not Found

**Root Cause**: Manual configuration of `OAuth2AuthorizationServerConfigurer` was incomplete, missing proper endpoint mappings

**Solution**: Changed to Spring's canonical pattern:

```java
OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
    .oidc(Customizer.withDefaults());
```

**Impact**: All OAuth endpoints now properly mapped and functional

### Fix #2: JWT Resource Server Enforcement

**Problem**: FHIR API requests were treated as anonymous even with valid Bearer tokens, resulting in 403 errors from the interceptor

**Root Cause**:

1. HAPI FHIR servlet mapping not properly matched by Spring Security
2. Anonymous authentication was creating fallback `AnonymousAuthenticationToken`
3. `BearerTokenAuthenticationFilter` was not processing JWT tokens

**Solution**: Three changes in `fhirFilterChain`:

```java
// 1. Use AntPathRequestMatcher for custom servlet mapping
.securityMatcher(new AntPathRequestMatcher("/fhir/**"))

// 2. Require authentication for all requests except metadata
.authorizeHttpRequests(authz -> authz
    .requestMatchers("/fhir/metadata").permitAll()
    .anyRequest().authenticated()
)

// 3. Disable anonymous authentication fallback
.anonymous(anonymous -> anonymous.disable())
```

**Impact**:

- Requests without Bearer tokens: **401 Unauthorized** at Spring Security layer
- Requests with valid Bearer tokens: `JwtAuthenticationToken` → Scope validation → ✅ Success

### Fix #3: OAuth Issuer URL

**Problem**: OAuth issuer was `http://localhost` (without port), causing 404s

**Root Cause**: `AuthorizationServerSettings` bean was created before `ConfigurationStartupService` set system properties

**Solution**:

1. Added `app.baseUrl` property to `application.yml` with environment variable support
2. Used `@Value("${app.baseUrl}")` in `authorizationServerSettings()` bean
3. Properly stripped `/fhir` suffix while preserving host and port

**Impact**: OAuth issuer correctly set to `http://localhost:8080` (or configured URL)

## Data Flow: OAuth Authorization Code Flow

```
1. Client → GET /oauth2/authorize?client_id=...&scope=patient/*.read
   ↓
2. Spring Security checks authentication
   ↓ (not authenticated)
3. Redirect → GET /login
   ↓
4. User submits credentials (fhiruser/password)
   ↓
5. UsernamePasswordAuthenticationFilter validates
   ↓
6. Session created, redirect back to /oauth2/authorize
   ↓
7. OAuth2AuthorizationEndpointFilter shows consent screen
   ↓
8. User approves scopes
   ↓
9. Authorization code generated, redirect to callback
   ↓ (Client receives code)
10. Client → POST /oauth2/token (code + client credentials)
    ↓
11. OAuth2TokenEndpointFilter validates code
    ↓
12. JWT access token generated with SMART claims
    ↓
13. Client receives access_token

--- Now client can access FHIR API ---

14. Client → GET /fhir/Patient (Authorization: Bearer <token>)
    ↓
15. Spring Security fhirFilterChain matches /fhir/**
    ↓
16. BearerTokenAuthenticationFilter extracts JWT from header
    ↓
17. JwtDecoder validates JWT signature and claims
    ↓
18. JwtAuthenticationToken created with scopes
    ↓
19. Request reaches HAPI FHIR RestfulServer
    ↓
20. SmartAuthorizationInterceptor checks scopes
    ↓ (patient/*.read allows Patient READ)
21. ✅ Request proceeds to PatientResourceProvider
```

## Configuration

### config.yaml

```yaml
app:
  baseUrl: "http://localhost:8080/fhir" # OAuth issuer derived from this

admin:
  email: "admin@couchbase.com"
  password: "Admin123!"
  name: "Admin"
```

### application.yml

```yaml
app:
  baseUrl: ${APP_BASE_URL:http://localhost:8080/fhir}
```

Allows environment variable override: `export APP_BASE_URL=https://your-domain.com/fhir`

## Security Model

### Public Endpoints (No Authentication)

- `/fhir/metadata` - FHIR CapabilityStatement
- `/actuator/health` - Health check
- `/login` - OAuth login page

### Admin UI Endpoints (Custom JWT)

- `/api/admin/**` - Protected with custom JWT from Admin UI login

### FHIR API Endpoints (OAuth 2.0 JWT)

- `/fhir/**` - Protected with OAuth access tokens + SMART scopes
- Metadata endpoint is public (explicitly permitted)

### OAuth Endpoints (Form Login)

- `/oauth2/authorize` - Requires form login (`fhiruser`/`password`)
- `/oauth2/token` - Client credentials authentication

## Production Readiness Checklist

- [ ] Replace in-memory client repository with Couchbase storage
- [ ] Store OAuth sessions in Redis/database (not in-memory)
- [ ] Use persistent JWT signing keys (not generated on startup)
- [ ] Connect UserDetailsService to Couchbase `fhir.Admin.users`
- [ ] Implement dynamic client registration
- [ ] Add patient context resolution (currently hardcoded)
- [ ] Enable HTTPS (required for production)
- [ ] Implement refresh token rotation
- [ ] Add rate limiting on OAuth endpoints
- [ ] Set up monitoring and audit logging
- [ ] Configure proper CORS for production domains
- [ ] Implement SMART launch context (EHR launch, standalone launch)
- [ ] Add support for opaque tokens (token introspection)

## Testing

See `docs/SMART_ON_FHIR_TESTING.md` for comprehensive testing instructions.

**Quick Test**:

```bash
# 1. Get authorization code (browser)
open "http://localhost:8080/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri=http://localhost:8080/authorized&scope=openid%20patient%2F*.read&state=test"

# 2. Login as fhiruser/password, approve scopes, copy code from redirect

# 3. Exchange code for token
curl -X POST http://localhost:8080/oauth2/token \
  -d "grant_type=authorization_code" \
  -d "code=YOUR_CODE" \
  -d "redirect_uri=http://localhost:8080/authorized" \
  -d "client_id=test-client" \
  -d "client_secret=test-secret"

# 4. Test FHIR API
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient
```

## Dependencies Added

```xml
<!-- pom.xml -->
<!-- Spring Authorization Server -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-authorization-server</artifactId>
</dependency>

<!-- Thymeleaf for login page -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

## Files Modified/Created

### New Files

- `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java`
- `backend/src/main/java/com/couchbase/fhir/auth/SmartScopes.java`
- `backend/src/main/java/com/couchbase/fhir/auth/SmartScopeValidator.java`
- `backend/src/main/java/com/couchbase/fhir/auth/SmartAuthorizationInterceptor.java`
- `backend/src/main/java/com/couchbase/fhir/auth/controller/LoginController.java`
- `backend/src/main/resources/templates/login.html`
- `docs/SMART_ON_FHIR_TESTING.md`
- `docs/SMART_IMPLEMENTATION_SUMMARY.md`

### Modified Files

- `backend/pom.xml` - Added OAuth dependencies
- `backend/src/main/java/com/couchbase/common/config/SecurityConfig.java` - Added FHIR filter chain with OAuth
- `backend/src/main/java/com/couchbase/common/config/CorsConfig.java` - Added CORS for OAuth endpoints
- `backend/src/main/java/com/couchbase/fhir/resources/config/FhirRestfulServer.java` - Registered interceptor
- `backend/src/main/java/com/couchbase/fhir/resources/provider/USCoreCapabilityProvider.java` - Added SMART metadata
- `backend/src/main/resources/application.yml` - Added `app.baseUrl` property

## Conclusion

The implementation successfully integrates Spring Authorization Server into the Couchbase FHIR Server, providing standards-compliant SMART on FHIR authorization. The critical fixes to the security filter chain and OAuth endpoint wiring ensure that JWT tokens are properly validated and scope enforcement works correctly at both the Spring Security layer and the FHIR resource layer.

The architecture maintains separation between Admin UI authentication and FHIR API OAuth, allowing for independent evolution of each system while sharing the same backend application.
