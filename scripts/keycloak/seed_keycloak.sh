#!/usr/bin/env bash
# seeds Keycloak with realm, client and a test user using Admin REST API
# Usage: ./scripts/keycloak/seed_keycloak.sh [path-to-.env]

set -euo pipefail
ENV_FILE="${1:-.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "Env file $ENV_FILE not found. Create it by running scripts/enable-keycloak.sh or provide path." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

if [ -z "${KEYCLOAK_URL:-}" ] || [ -z "${KEYCLOAK_REALM:-}" ] || [ -z "${KEYCLOAK_ADMIN_USERNAME:-}" ] || [ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  echo "Missing KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_ADMIN_USERNAME or KEYCLOAK_ADMIN_PASSWORD in $ENV_FILE" >&2
  exit 1
fi

API_BASE="$KEYCLOAK_URL/admin/realms"
MASTER_TOKEN_URL="$KEYCLOAK_URL/realms/master/protocol/openid-connect/token"

# Wait for Keycloak to be ready
echo "Waiting for Keycloak at $KEYCLOAK_URL ..."
for i in {1..30}; do
  if curl -sSf "$KEYCLOAK_URL" >/dev/null 2>&1; then
    echo "Keycloak is responding"
    break
  fi
  sleep 2
done

# Get admin token (admin-cli with password grant)
ADMIN_TOKEN=$(curl -s -X POST "$MASTER_TOKEN_URL" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=${KEYCLOAK_ADMIN_USERNAME}&password=${KEYCLOAK_ADMIN_PASSWORD}" \
  | jq -r '.access_token')

if [ -z "$ADMIN_TOKEN" ] || [ "$ADMIN_TOKEN" = "null" ]; then
  echo "Failed to obtain admin token from Keycloak. Check admin credentials." >&2
  exit 1
fi

echo "Admin token acquired (truncated): ${ADMIN_TOKEN:0:20}..."

# Create realm if it doesn't exist
if curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $ADMIN_TOKEN" "$API_BASE/$KEYCLOAK_REALM" | grep -q "200"; then
  echo "Realm '$KEYCLOAK_REALM' already exists"
else
  echo "Creating realm '$KEYCLOAK_REALM'"
  curl -s -X POST "$KEYCLOAK_URL/admin/realms" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @"$(dirname "$0")/realm.json" | jq .
fi

# Create client (fhir-server) if not exists
CLIENTS_URL="$API_BASE/$KEYCLOAK_REALM/clients"
EXISTS=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$CLIENTS_URL?clientId=${KEYCLOAK_CLIENT_ID}" | jq 'length')
if [ "$EXISTS" -gt 0 ]; then
  echo "Client '${KEYCLOAK_CLIENT_ID}' already exists"
else
  echo "Creating client '${KEYCLOAK_CLIENT_ID}'"
  cat > /tmp/client-payload.json <<JSON
{
  "clientId": "${KEYCLOAK_CLIENT_ID}",
  "enabled": true,
  "publicClient": false,
  "protocol": "openid-connect",
  "redirectUris": ["http://localhost:8080/authorized"],
  "serviceAccountsEnabled": true,
  "directAccessGrantsEnabled": true
}
JSON
  curl -s -X POST "$CLIENTS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @/tmp/client-payload.json
  echo "Created client (requested direct access grants)."
fi

# Ensure the client has direct access grants enabled and a client secret (for confidential client)
CLIENT_INTERNAL_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$CLIENTS_URL?clientId=${KEYCLOAK_CLIENT_ID}" | jq -r '.[0].id // empty')
if [ -z "$CLIENT_INTERNAL_ID" ]; then
  echo "Failed to locate internal client id for ${KEYCLOAK_CLIENT_ID}" >&2
  exit 1
fi

echo "Configuring client (id=$CLIENT_INTERNAL_ID) to allow ROPC..."

# Fetch current representation
CLIENT_REPR=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$CLIENTS_URL/$CLIENT_INTERNAL_ID")

# Patch representation: enable directAccessGrantsEnabled and serviceAccountsEnabled, ensure publicClient=false
UPDATED=$(echo "$CLIENT_REPR" | jq '. + {directAccessGrantsEnabled: true, serviceAccountsEnabled: true, publicClient: false}')

if [ -n "${KEYCLOAK_CLIENT_SECRET:-}" ]; then
  # If a secret is provided in env, set it explicitly
  UPDATED=$(echo "$UPDATED" | jq --arg sec "$KEYCLOAK_CLIENT_SECRET" '. + {secret: $sec}')
fi

# Put updated representation back
curl -s -X PUT "$CLIENTS_URL/$CLIENT_INTERNAL_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$UPDATED" >/dev/null

# If no client secret was provided, generate one via the client-secret endpoint
if [ -z "${KEYCLOAK_CLIENT_SECRET:-}" ]; then
  echo "No KEYCLOAK_CLIENT_SECRET provided; generating one via Keycloak API..."
  GEN=$(curl -s -X POST "$CLIENTS_URL/$CLIENT_INTERNAL_ID/client-secret" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json")
  # Try common fields for returned secret
  NEW_SECRET=$(echo "$GEN" | jq -r '.value // .secret.value // .secret // empty')
  if [ -n "$NEW_SECRET" ]; then
    echo "Generated client secret for ${KEYCLOAK_CLIENT_ID}: $NEW_SECRET"
    echo "Update your .env KEYCLOAK_CLIENT_SECRET with this value for future runs."
  else
    echo "Warning: could not extract generated client secret from Keycloak response: $GEN" >&2
  fi
fi

# Optionally create a test user if not present
USERS_URL="$API_BASE/$KEYCLOAK_REALM/users"
TEST_USER_EMAIL="smart.user@example.com"
USER_EXISTS=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$USERS_URL?username=${TEST_USER_EMAIL}" | jq 'length')
if [ "$USER_EXISTS" -gt 0 ]; then
  echo "Test user $TEST_USER_EMAIL already exists"
else
  echo "Creating test user $TEST_USER_EMAIL"
  cat > /tmp/user-payload.json <<JSON
{
  "username": "${TEST_USER_EMAIL}",
  "enabled": true,
  "firstName": "Smart",
  "lastName": "User",
  "email": "${TEST_USER_EMAIL}",
  "credentials": [{"type":"password","value":"password123","temporary":false}]
}
JSON
  curl -s -X POST "$USERS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @/tmp/user-payload.json
  echo "Created test user (username: $TEST_USER_EMAIL, password: password123)"
fi

echo "Seeding complete."
