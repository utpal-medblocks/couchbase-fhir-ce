#!/usr/bin/env bash
set -euo pipefail
# Utility script to regenerate SHA256 checksums for installer integrity verification.
# It updates the hash lines for docker-compose.yml (source: docker-compose.user.yml)
# and haproxy.cfg inside install.sh.
#
# Usage: ./scripts/update-checksums.sh
# Optional env vars:
#   SKIP_HAPROXY=1   -> only update docker-compose hash
#   DRY_RUN=1        -> show planned changes but do not modify install.sh
#
# Requires: sha256sum or shasum, perl (for in-place replacement), awk.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

INSTALL_SH="install.sh"
DC_SRC="docker-compose.user.yml"   # Source file downloaded as docker-compose.yml
HAPROXY_FILE="haproxy.cfg"

if [[ ! -f $INSTALL_SH ]]; then
  echo "Error: $INSTALL_SH not found in $ROOT_DIR" >&2; exit 1
fi
if [[ ! -f $DC_SRC ]]; then
  echo "Error: $DC_SRC not found (expected at repo root)" >&2; exit 1
fi
if [[ ! -f $HAPROXY_FILE ]]; then
  echo "Error: $HAPROXY_FILE not found (expected at repo root)" >&2; exit 1
fi

hash_cmd=""
if command -v sha256sum >/dev/null 2>&1; then
  hash_cmd="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
  hash_cmd="shasum -a 256"
else
  echo "Error: Need sha256sum or shasum available." >&2; exit 1
fi

get_hash() { # file
  $hash_cmd "$1" | awk '{print $1}'
}

new_dc_hash=$(get_hash "$DC_SRC")
if [[ -z "${SKIP_HAPROXY:-}" ]]; then
  new_haproxy_hash=$(get_hash "$HAPROXY_FILE")
else
  # Extract existing haproxy hash to preserve
  new_haproxy_hash=$(grep -E "[0-9a-f]{64}  haproxy\.cfg" "$INSTALL_SH" | head -1 | awk '{print $1}')
fi

echo "docker-compose (from $DC_SRC) -> $new_dc_hash"
echo "haproxy.cfg -> $new_haproxy_hash"

if [[ -n "${DRY_RUN:-}" ]]; then
  echo "DRY_RUN set: not modifying $INSTALL_SH"; exit 0
fi

backup="$INSTALL_SH.bak.$(date +%s)"
cp "$INSTALL_SH" "$backup"

# Replace in both checksum blocks (sha256sum and shasum fallback) using perl multi-line edit.
perl -0777 -i -pe "s/[0-9a-f]{64}(\s+docker-compose\.yml)/${new_dc_hash}\\1/g" "$INSTALL_SH"
perl -0777 -i -pe "s/[0-9a-f]{64}(\s+haproxy\.cfg)/${new_haproxy_hash}\\1/g" "$INSTALL_SH"

echo "Updated hashes in $INSTALL_SH (backup: $backup)"

grep -nE "${new_dc_hash}  docker-compose\.yml" "$INSTALL_SH" || { echo "Failed to insert docker-compose hash" >&2; exit 1; }
grep -nE "${new_haproxy_hash}  haproxy\.cfg" "$INSTALL_SH" || { echo "Failed to insert haproxy hash" >&2; exit 1; }

echo "Done."