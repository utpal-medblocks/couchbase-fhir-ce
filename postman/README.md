# Postman Collection for Couchbase FHIR with OAuth

## Quick Start

1. **Import Collection**:
   - Open Postman
   - Click **Import** → **Upload Files**
   - Select `Couchbase-FHIR-OAuth.postman_collection.json`

2. **Use the Collection**:
   - The collection has **automatic token generation**
   - Just run any FHIR API request - it will automatically get a fresh OAuth token!
   - No need to manually copy/paste tokens

3. **Test It**:
   - Try **FHIR Resources → Search Patients**
   - Check the Postman console (View → Show Postman Console) to see token generation

## How It Works

The collection has a **pre-request script** that:
1. Checks if you have a valid access token
2. If not (or if expired), automatically calls `/oauth2/token` with client credentials
3. Saves the token to collection variables
4. Uses it for all FHIR API requests

## Configuration

Collection variables (already set with defaults):

| Variable | Default Value | Description |
|----------|---------------|-------------|
| `base_url` | `http://localhost:8080` | FHIR server URL |
| `client_id` | `test-client` | OAuth client ID |
| `client_secret` | `test-secret` | OAuth client secret |
| `scope` | `patient/*.read` | Default OAuth scope |

To change these:
1. Click on the collection name
2. Go to **Variables** tab
3. Edit the **Current Value** column

## Changing Scopes

To test with different scopes:

1. **Change default scope**:
   - Edit collection variable `scope` to one of:
     - `patient/*.read` - Read all patient resources
     - `patient/*.write` - Write patient resources (includes read)
     - `user/*.read` - Read all resources in user context
     - `system/*.read` - Read all resources (system-wide)

2. **Clear cached token**:
   - Go to collection **Variables** tab
   - Clear the `access_token` and `token_expiry` values
   - Next request will generate a new token with the new scope

## Available Requests

### OAuth Endpoints
- **Get Access Token (Client Credentials)** - Manually get a token (useful for debugging)
- **OAuth Server Metadata** - View server capabilities and supported scopes

### FHIR Resources
- **Get CapabilityStatement** - Public endpoint, no auth required
- **Search Patients** - Get all patients (requires `patient/*.read`)
- **Get Patient by ID** - Get specific patient
- **Create Patient** - Create new patient (requires `patient/*.write` - will fail with read-only scope)
- **Search Observations** - Get observations

## Testing Scope Enforcement

1. **Test READ with read scope** (should work):
   - Scope: `patient/*.read`
   - Request: **Search Patients** → ✅ Success

2. **Test WRITE with read scope** (should fail):
   - Scope: `patient/*.read`
   - Request: **Create Patient** → ❌ 403 Forbidden

3. **Test WRITE with write scope** (should work):
   - Change scope to `patient/*.write`
   - Clear token variables
   - Request: **Create Patient** → ✅ Success

## Viewing Token Details

To see what's in your access token:

1. Run any FHIR request
2. Open Postman Console (View → Show Postman Console)
3. Look for log messages:
   ```
   ✅ Access token obtained!
   📋 Scope: patient/*.read
   ⏰ Expires in: 3599 seconds
   ```

## Troubleshooting

### Token Not Generated
- Check Postman Console for error messages
- Ensure backend is running on `http://localhost:8080`
- Verify client credentials are correct

### 401 Unauthorized
- Token might be expired (auto-renewed on next request)
- Check that the request is using collection auth (not "No Auth")

### 403 Forbidden
- Insufficient scope for the operation
- Try with broader scope (e.g., `patient/*.write` instead of `patient/*.read`)

