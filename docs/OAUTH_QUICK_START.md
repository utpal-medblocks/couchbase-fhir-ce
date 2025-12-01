# OAuth Quick Start - TL;DR

## Get Token for Testing (No Browser!)

```bash
./get-token.sh
```

That's it! Token is valid for **24 hours** by default.

---

## Change Token Expiry

```bash
# For 24-hour stress test
export OAUTH_TOKEN_EXPIRY_HOURS=25

# Restart backend
cd backend && ./mvnw spring-boot:run

# Get token (now valid for 25 hours)
./get-token.sh
```

---

## Use Token in Postman

**Option A: Import Collection (Auto-Token)**
1. Import `postman/Couchbase-FHIR-OAuth.postman_collection.json`
2. Run any FHIR request - token generated automatically!

**Option B: Manual**
1. Run `./get-token.sh` and copy the token
2. In Postman: Authorization → Type: Bearer Token → Paste token

---

## Use Token with curl

```bash
# Get token and save to variable
source .token.env  # Created automatically by get-token.sh

# Or manually:
export ACCESS_TOKEN="your_token_here"

# Make FHIR requests
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient
```

---

## Why Browser Login Exists

**Browser login** = For third-party apps that need user consent (like EHR integrations)

**Token script** = For testing, development, your own apps

You can **ignore the browser flow** for testing - just use `./get-token.sh`!

---

## Token Details

- **Default expiry**: 24 hours
- **Scopes**: `system/*.read system/*.write` (full CRUD on all resources)
- **Type**: JWT Bearer token
- **Yes, it's long**: Normal for JWTs (they contain all auth info)

---

## For 24-Hour Stress Test

```bash
# Before starting backend:
export OAUTH_TOKEN_EXPIRY_HOURS=25

# Start backend
cd backend && ./mvnw spring-boot:run

# Get token (valid for 25 hours)
./get-token.sh

# Run your stress test with that token for 24 hours
```

---

## Files

- `./get-token.sh` - Get OAuth token (no browser)
- `postman/` - Postman collection with auto-token
- `.token.env` - Auto-generated, contains current token
- `docs/OAUTH_TOKEN_CONFIGURATION.md` - Full documentation

---

## Common Commands

```bash
# Get token with full access (default)
./get-token.sh

# Get token with specific scope
./get-token.sh "patient/*.read"

# Test FHIR API
source .token.env
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/fhir/Patient

# Check token expiry
echo $ACCESS_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '.exp'
```

