#!/usr/bin/env bash
# enable-keycloak.sh
# Adds Keycloak service to docker-compose.yaml, patches application.yml and exports env vars
# Usage: ./scripts/enable-keycloak.sh [path/to/config.yaml]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_FILE="${1:-$ROOT_DIR/config.yaml}"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Config file $CONFIG_FILE not found. Try passing path to your config.yaml." >&2
  exit 1
fi

# Parse config.yaml using helper script and eval its KEY=VALUE output
if ! SCRIPT_OUTPUT=$(python3 "$SCRIPT_DIR/keycloak/parse_config.py" "$CONFIG_FILE"); then
  echo "Failed to parse $CONFIG_FILE" >&2
  exit 2
fi
# shellcheck disable=SC2086
eval "$SCRIPT_OUTPUT"
# Normalize enabled (accept true/1/yes case-insensitive)
if printf '%s' "$KEYCLOAK_ENABLED" | grep -qiE '^(true|1|yes)$'; then
  KEYCLOAK_ENABLED=true
else
  KEYCLOAK_ENABLED=false
fi

if [ "$KEYCLOAK_ENABLED" != "true" ]; then
  echo "Keycloak integration is not enabled in $CONFIG_FILE (keycloak.enabled != true). Nothing to do."
  exit 0
fi

echo "Keycloak is enabled. URL=$KEYCLOAK_URL realm=$KEYCLOAK_REALM"

# 1) Insert Keycloak service into all docker-compose*.yml files using Python helper
if python3 "$SCRIPT_DIR/keycloak/insert_keycloak_compose.py" "$ROOT_DIR"; then
  echo "Compose files updated (if any)."
else
  echo "Failed to update compose files with Keycloak service."
  exit 1
fi

# 2) Export env vars to .env (so docker-compose picks them up)
ENV_FILE="$ROOT_DIR/.env"
mkdir -p "$ROOT_DIR"
if [ ! -f "$ENV_FILE" ]; then
  touch "$ENV_FILE"
fi

# helper to set or replace env var in .env using bash associative array (preserves unrelated vars)
set_env() {
  local name="$1" value="$2"
  declare -A envmap
  while IFS='=' read -r k v; do
    # skip empty lines
    [ -z "$k" ] && continue
    envmap["$k"]="$v"
  done < "$ENV_FILE"
  envmap["$name"]="$value"
  # write back
  : > "$ENV_FILE.tmp"
  for k in "${!envmap[@]}"; do
    printf '%s=%s\n' "$k" "${envmap[$k]}" >> "$ENV_FILE.tmp"
  done
  mv "$ENV_FILE.tmp" "$ENV_FILE"
}

# write values (may be empty strings)
set_env "KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_USERNAME"
set_env "KEYCLOAK_ADMIN_PASSWORD" "$KEYCLOAK_ADMIN_PASSWORD"
set_env "KEYCLOAK_CLIENT_ID" "$KEYCLOAK_CLIENT_ID"
set_env "KEYCLOAK_CLIENT_SECRET" "$KEYCLOAK_CLIENT_SECRET"
set_env "KEYCLOAK_URL" "$KEYCLOAK_URL"
set_env "KEYCLOAK_REALM" "$KEYCLOAK_REALM"
set_env "KEYCLOAK_ENABLED" "true"

# Derive JWKS URI and store it in .env for application.yml consumption
if [ -n "$KEYCLOAK_URL" ] && [ -n "$KEYCLOAK_REALM" ]; then
  JWKS_URI="$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/certs"
  set_env "KEYCLOAK_JWKS_URI" "$JWKS_URI"
  echo "Derived KEYCLOAK_JWKS_URI=$JWKS_URI"
else
  echo "KEYCLOAK_URL or KEYCLOAK_REALM missing; not setting KEYCLOAK_JWKS_URI in .env" >&2
fi

echo "Wrote Keycloak env vars to $ENV_FILE"

# 2b) Generate a realm.json for import (overwrites scripts/keycloak/realm.json)
REALM_DIR="$ROOT_DIR/scripts/keycloak"
mkdir -p "$REALM_DIR"
REALM_FILE="$REALM_DIR/realm.json"

REALM_NAME="${KEYCLOAK_REALM:-fhir}"
CLIENT_ID_VAL="${KEYCLOAK_CLIENT_ID:-fhir-server}"
REDIRECT_URI="http://localhost:8080/authorized"

cat > "$REALM_FILE" <<JSON
{
  "realm": "${REALM_NAME}",
  "enabled": true,
  "clients": [
    {
      "clientId": "${CLIENT_ID_VAL}",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "redirectUris": ["${REDIRECT_URI}"],
      "serviceAccountsEnabled": true
    }
  ]
}
JSON

echo "Wrote Keycloak realm import file to $REALM_FILE"

# 3) Patch backend application.yml to include resolved Keycloak properties (inject concrete values)
APP_YML="$ROOT_DIR/backend/src/main/resources/application.yml"
if [ -f "$APP_YML" ]; then
  # Use Python helper to merge values safely (creates .bak if not present)
  echo "Patching $APP_YML with Keycloak settings (use-keycloak=$KEYCLOAK_ENABLED)"
  python3 "$SCRIPT_DIR/keycloak/patch_application_yml.py" "$APP_YML" "$KEYCLOAK_ENABLED" "${JWKS_URI:-}" || {
    echo "Failed to patch $APP_YML with Keycloak settings" >&2
  }
else
  echo "Warning: $APP_YML not found; cannot patch application.yml" >&2
fi

# 4) Suggest HAProxy changes (append guidance to haproxy.cfg if present)
HAPROXY_CFG="$ROOT_DIR/haproxy.cfg"
if [ -f "$HAPROXY_CFG" ]; then
  # Backup haproxy.cfg before any changes
  if [ ! -f "$HAPROXY_CFG.bak" ]; then
    cp "$HAPROXY_CFG" "$HAPROXY_CFG.bak" || true
  fi

  if grep -qE '^backend[ \t]+keycloak_backend' "$HAPROXY_CFG"; then
    echo "HAProxy already configured for Keycloak (marker found)."
  else
    echo "Patching $HAPROXY_CFG to add Keycloak routing without overwriting existing frontend"

    if grep -qE '^\s*acl\s+url_keycloak' "$HAPROXY_CFG"; then
      echo "HAProxy already configured for Keycloak (acl found)."
    else
      if grep -qE '^frontend[ \t]+http-in' "$HAPROXY_CFG" || grep -qE '^frontend[ \t]+http_front' "$HAPROXY_CFG"; then
        echo "Inserting Keycloak ACL/use_backend into existing frontend (http_in or http_front) using heuristics"
        awk '
        BEGIN {
          in_fe=0; acl_ins=0; use_ins=0; target_regex="^frontend[ \t]+(http-in|http_front)"
        }
        {
          # Detect start of target frontend
          if ($0 ~ target_regex) {
            in_fe=1
            print $0
            next
          }

          # If another frontend starts while we were in target, finalize inserts and leave
          if (in_fe && $0 ~ /^frontend[ \t]+/) {
            if (!acl_ins) { print "  acl url_keycloak path_beg /auth"; acl_ins=1 }
            if (!use_ins) { print "  use_backend keycloak_backend if url_keycloak"; use_ins=1 }
            in_fe=0
          }

          # While inside the target frontend, insert before the first acl and first use_backend
          if (in_fe && !acl_ins && $0 ~ /^[ \t]*acl[ \t]+/) {
            print "  acl url_keycloak path_beg /auth"
            acl_ins=1
          }
          if (in_fe && !use_ins && $0 ~ /^[ \t]*use_backend[ \t]+/) {
            print "  use_backend keycloak_backend if url_keycloak"
            use_ins=1
          }

          print $0
        }
        END {
          # If file ended while still in the frontend, ensure inserts exist
          if (in_fe) {
            if (!acl_ins) print "  acl url_keycloak path_beg /auth"
            if (!use_ins) print "  use_backend keycloak_backend if url_keycloak"
          }
        }' "$HAPROXY_CFG" > "$HAPROXY_CFG.tmp" && mv "$HAPROXY_CFG.tmp" "$HAPROXY_CFG"
      else
        echo "Adding minimal frontend http_front with Keycloak ACL and backend"
        cat >> "$HAPROXY_CFG" <<HC_FOOTER

# KEYCLOAK BACKEND - added by scripts/enable-keycloak.sh
frontend http_front
  acl url_keycloak path_beg /auth
  use_backend keycloak_backend if url_keycloak

HC_FOOTER
      fi

      if ! grep -qE '^backend[ \t]+keycloak_backend' "$HAPROXY_CFG"; then
        cat >> "$HAPROXY_CFG" <<HC_BACK

backend keycloak_backend
  server keycloak keycloak:8080 check

HC_BACK
        echo "Appended keycloak backend to $HAPROXY_CFG"
      else
        echo "Keycloak backend already present in $HAPROXY_CFG"
      fi
    fi
  fi
else
  echo "haproxy.cfg not found; skipping HAProxy patch. If you use HAProxy, add a backend mapping for Keycloak to route /auth to keycloak:8080"
fi

cat <<MSG

Next steps:
- Start Keycloak via docker-compose: docker compose up -d keycloak
- If you provided admin credentials in config.yaml, you can seed realm/clients using Keycloak Admin REST API or import a realm.
- Ensure environment variables in .env are set and that your HAProxy routes /auth to the Keycloak container.

Example JWKS URI (set KEYCLOAK_JWKS_URI in .env):
${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs

Run this script again if you update 'config.yaml' values.
MSG

exit 0
