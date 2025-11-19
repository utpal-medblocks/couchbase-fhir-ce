# OAuth Token Configuration Guide

## Token Expiry Settings

The OAuth access token expiry is **fully configurable** via environment variable.

### Default Settings

- **Development/Testing**: 24 hours (86400 seconds)
- **Production Recommendation**: 1 hour (3600 seconds)

### How to Configure

Set the `OAUTH_TOKEN_EXPIRY_HOURS` environment variable before starting the backend:

```bash
# 24 hour stress test (25 hours to be safe)
export OAUTH_TOKEN_EXPIRY_HOURS=25
./mvnw spring-boot:run

# Production setting (1 hour)
export OAUTH_TOKEN_EXPIRY_HOURS=1
./mvnw spring-boot:run

# Week-long testing (168 hours)
export OAUTH_TOKEN_EXPIRY_HOURS=168
./mvnw spring-boot:run

# Never expire (for debugging only - NOT recommended)
export OAUTH_TOKEN_EXPIRY_HOURS=8760
./mvnw spring-boot:run
```

### Docker/Docker Compose

Add to your `docker-compose.yml`:

```yaml
services:
  fhir-backend:
    environment:
      - OAUTH_TOKEN_EXPIRY_HOURS=24
```

Or pass as command-line argument:

```bash
docker run -e OAUTH_TOKEN_EXPIRY_HOURS=24 couchbase-fhir-backend
```

## OAuth Flows - When to Use Each

### Flow 1: Client Credentials Grant (Recommended for Testing)

**Use this for**:

- Development and testing
- Backend services
- Automated tests
- Stress tests
- Postman/curl requests

**How to get token**:

```bash
./get-token.sh
```

**Advantages**:

- ✅ No browser needed
- ✅ No user login
- ✅ No consent screen
- ✅ Instant token
- ✅ Perfect for automation

**When token expires**: Just run `./get-token.sh` again (or use refresh token)

---

### Flow 2: Authorization Code Grant (For Third-Party Apps)

**Use this for**:

- Third-party mobile apps
- EHR integrations
- Web apps that need user consent
- SMART App Launch from EHR portals

**How it works**:

1. User clicks "Connect to FHIR Server" in your app
2. Redirected to login page
3. User logs in and approves scopes
4. App receives authorization code
5. App exchanges code for access token

**Advantages**:

- ✅ User consent/authorization
- ✅ Secure for third-party apps
- ✅ Standard SMART on FHIR flow
- ✅ Supports refresh tokens

**When to use**: Production apps that need user approval

---

## Stress Testing with Long-Lived Tokens

For 24-hour stress tests, you have two options:

### Option 1: Long-Lived Token (Simplest)

```bash
# Set expiry to 25 hours (buffer for 24h test)
export OAUTH_TOKEN_EXPIRY_HOURS=25

# Restart backend
cd backend && ./mvnw spring-boot:run

# Get token (valid for 25 hours)
./get-token.sh

# Run stress test
./run-stress-test.sh
```

### Option 2: Auto-Refresh Token (More Realistic)

Your stress test script can automatically get fresh tokens:

```bash
#!/bin/bash
# stress-test-with-auto-token.sh

get_fresh_token() {
  curl -s -X POST http://localhost:8080/oauth2/token \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
    -d "grant_type=client_credentials" \
    -d "scope=system/*.read system/*.write" | jq -r '.access_token'
}

# Get initial token
ACCESS_TOKEN=$(get_fresh_token)

# Run stress test for 24 hours
END_TIME=$(($(date +%s) + 86400)) # 24 hours from now

while [ $(date +%s) -lt $END_TIME ]; do
  # Refresh token every 45 minutes (if default is 1 hour)
  ACCESS_TOKEN=$(get_fresh_token)

  # Run your load test commands
  for i in {1..1000}; do
    curl -H "Authorization: Bearer $ACCESS_TOKEN" \
      http://localhost:8080/fhir/Patient &
  done

  wait
  sleep 5
done
```

## Token Information

### What's in an Access Token?

OAuth access tokens are **JWT (JSON Web Tokens)** containing:

```json
{
  "sub": "test-client",
  "aud": "test-client",
  "scope": ["system/*.read", "system/*.write"],
  "iss": "http://localhost:8080",
  "exp": 1234567890, // Expiry timestamp
  "iat": 1234567890, // Issued at timestamp
  "jti": "unique-token-id"
}
```

### Why So Long?

JWT tokens are long because they contain:

- **Header** (algorithm, key ID)
- **Payload** (claims, scopes, user info)
- **Signature** (cryptographic signature for validation)

All encoded in Base64. This is **normal and expected** for JWTs.

### Security Note

⚠️ **Don't share tokens!** They grant full access to your FHIR server.

- Store in environment variables, not code
- Don't commit `.token.env` to git (already in `.gitignore`)
- Use short expiry (1 hour) in production
- Rotate tokens regularly

## Refresh Tokens

**Already configured!** Refresh tokens are valid for 30 days and can be used to get new access tokens without re-authenticating.

### Using Refresh Tokens

```bash
# Get initial tokens (authorization code flow only)
# Response includes both access_token and refresh_token

# When access token expires, use refresh token:
curl -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
  -d "grant_type=refresh_token" \
  -d "refresh_token=YOUR_REFRESH_TOKEN"
```

**Note**: Client credentials grant doesn't use refresh tokens (it can just request a new token directly).

## Production Recommendations

1. **Token Expiry**: Set to 1 hour

   ```bash
   export OAUTH_TOKEN_EXPIRY_HOURS=1
   ```

2. **Enable HTTPS**: OAuth requires HTTPS in production

3. **Rotate Keys**: Implement JWT signing key rotation

4. **Monitor**: Log token generation and usage

5. **Rate Limiting**: Limit token requests per client

6. **Persistent Storage**: Move from in-memory to database storage for tokens

## Summary

- ✅ **Default token expiry**: 24 hours (perfect for testing)
- ✅ **Configurable**: Set `OAUTH_TOKEN_EXPIRY_HOURS` environment variable
- ✅ **For 24h stress test**: Set to 25 hours, get one token, use for entire test
- ✅ **For testing**: Use `./get-token.sh` (client credentials, no browser)
- ✅ **For third-party apps**: Use authorization code flow (browser login)
- ✅ **Token is long**: Normal for JWT (contains all authorization info)
