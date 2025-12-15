#!/usr/bin/env bash
# disable-keycloak.sh
# Restores backups created by enable-keycloak.sh and removes Keycloak traces when possible

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Disabling Keycloak integration and restoring backups where available..."

# 1) Restore docker-compose backups (*.yml.bak or *.yaml.bak)
shopt -s nullglob
restored=0
for bak in "$ROOT_DIR"/docker-compose*.yml.bak "$ROOT_DIR"/docker-compose*.yaml.bak; do
  if [ -f "$bak" ]; then
    orig="${bak%.bak}"
    echo "Restoring $orig from $bak"
    cp -f "$bak" "$orig"
    restored=1
  fi
done

if [ "$restored" -eq 0 ]; then
  echo "No docker-compose backups found. Attempting best-effort removal from compose files."
  if command -v python3 >/dev/null 2>&1; then
    python3 "$SCRIPT_DIR/keycloak/remove_keycloak_compose.py" "$ROOT_DIR" || echo "Warning: automatic removal failed"
  else
    echo "Python3 not available; cannot attempt automatic removal of Keycloak from compose files."
  fi
fi

# 2) Restore application.yml if backup exists
APP_YML="$ROOT_DIR/backend/src/main/resources/application.yml"
if [ -f "$APP_YML.bak" ]; then
  echo "Restoring application.yml from $APP_YML.bak"
  cp -f "$APP_YML.bak" "$APP_YML"
else
  echo "No application.yml.bak found; leaving application.yml as-is"
fi

# 3) Restore haproxy.cfg if backup exists
HAPROXY_CFG="$ROOT_DIR/haproxy.cfg"
if [ -f "$HAPROXY_CFG.bak" ]; then
  echo "Restoring haproxy.cfg from $HAPROXY_CFG.bak"
  cp -f "$HAPROXY_CFG.bak" "$HAPROXY_CFG"
else
  echo "No haproxy.cfg.bak found; attempting to remove Keycloak-specific sections"
  # Attempt a best-effort removal: remove backend keycloak_backend block and acl lines
  if grep -qE '^backend[ \t]+keycloak_backend' "$HAPROXY_CFG" 2>/dev/null || grep -q 'url_keycloak' "$HAPROXY_CFG" 2>/dev/null; then
    echo "Removing Keycloak entries from haproxy.cfg (in-place edit)"
    # Remove backend block
    awk 'BEGIN{in_block=0} /^
backend[ \t]+keycloak_backend/ {in_block=1; next} /^backend[ \t]+/ {if(in_block){in_block=0} } { if(!in_block) print $0 }' "$HAPROXY_CFG" > "$HAPROXY_CFG.tmp" || true
    # Remove acl/use_backend lines referencing url_keycloak
    grep -vE 'url_keycloak|keycloak_backend' "$HAPROXY_CFG.tmp" > "$HAPROXY_CFG" || true
    rm -f "$HAPROXY_CFG.tmp"
  else
    echo "No Keycloak markers found in haproxy.cfg"
  fi
fi

# 4) Clean .env Keycloak vars (remove KEYCLOAK_* entries)
ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  echo "Cleaning Keycloak environment variables from $ENV_FILE"
  grep -v '^KEYCLOAK_' "$ENV_FILE" > "$ENV_FILE.tmp" || true
  mv "$ENV_FILE.tmp" "$ENV_FILE"
else
  echo "No .env file found; skipping .env cleanup"
fi

echo "Keycloak disable script completed."
echo "If you wish to fully revert other generated artifacts (e.g., scripts/keycloak/realm.json), remove them manually."
