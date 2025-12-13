#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost/auth}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-fhir}"
ADMIN_USER="${KEYCLOAK_ADMIN_USERNAME:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

echo "Using Keycloak at $KEYCLOAK_URL realm=$KEYCLOAK_REALM"

token() {
  curl -sS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password&client_id=admin-cli&username=${ADMIN_USER}&password=${ADMIN_PASS}" | jq -r .access_token
}

if ! command -v jq >/dev/null 2>&1; then
  echo "Please install jq to run this test script" >&2
  exit 1
fi

TKN=$(token)
if [ -z "$TKN" ] || [ "$TKN" = "null" ]; then
  echo "Failed to obtain admin token" >&2
  exit 1
fi

TEST_USER="test-user-$(date +%s)"
echo "Creating test user: $TEST_USER"
CREATE_PAYLOAD=$(jq -n --arg u "$TEST_USER" --arg e "$TEST_USER@example.org" --arg p "password" '{username:$u, email:$e, enabled:true, firstName:$u, credentials:[{type:"password", value:$p, temporary:false}]}')

resp=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$KEYCLOAK_REALM/users" \
  -H "Authorization: Bearer $TKN" -H 'Content-Type: application/json' -d "$CREATE_PAYLOAD")

if [ "$resp" != "201" ]; then
  echo "Failed to create user, HTTP $resp" >&2
  exit 1
fi

echo "User created, verifying..."
sleep 1
search=$(curl -sS -G "$KEYCLOAK_URL/admin/realms/$KEYCLOAK_REALM/users" --data-urlencode "username=$TEST_USER" -H "Authorization: Bearer $TKN")
echo "$search" | jq .

# cleanup
id=$(echo "$search" | jq -r '.[0].id')
if [ -n "$id" ] && [ "$id" != "null" ]; then
  echo "Deleting test user id=$id"
  curl -sS -X DELETE "$KEYCLOAK_URL/admin/realms/$KEYCLOAK_REALM/users/$id" -H "Authorization: Bearer $TKN"
  echo "Done"
  exit 0
else
  echo "Could not find created user" >&2
  exit 1
fi
